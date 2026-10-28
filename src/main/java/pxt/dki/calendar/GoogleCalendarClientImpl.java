package pxt.dki.calendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.client.json.gson.GsonFactory;
import pxt.dki.common.time.BusinessTime;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;

/**
 * Real Google Calendar client, selected by {@link CalendarClientsConfig} when calendar sync is
 * enabled. Uses a service account key; optionally impersonates a Workspace user (Domain-Wide
 * Delegation) which is required for inviting attendees.
 */
public class GoogleCalendarClientImpl implements GoogleCalendarClient {

    private final CalendarProperties properties;
    private volatile Calendar calendar;

    public GoogleCalendarClientImpl(CalendarProperties properties) {
        this.properties = properties;
    }

    /**
     * Built lazily on first use so the application can boot while the key is missing;
     * failures surface per outbox item, get retried, and stay visible to the admin.
     */
    private Calendar calendar() {
        Calendar local = calendar;
        if (local == null) {
            synchronized (this) {
                if (calendar == null) {
                    calendar = buildCalendar();
                }
                local = calendar;
            }
        }
        return local;
    }

    private Calendar buildCalendar() {
        try (FileInputStream key = new FileInputStream(properties.serviceAccountKeyPath())) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(key)
                    .createScoped(CalendarScopes.CALENDAR);
            if (properties.impersonateUser() != null && !properties.impersonateUser().isBlank()) {
                credentials = credentials.createDelegated(properties.impersonateUser());
            }
            return new Calendar.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("dki-room-booking")
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize Google Calendar client: " + e.getMessage(), e);
        }
    }

    @Override
    public String createEvent(String calendarId, CalendarEvent event) {
        try {
            Event created = calendar().events().insert(calendarId, toGoogle(event)).execute();
            return created.getId();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public void updateEvent(String calendarId, String eventId, CalendarEvent event) {
        try {
            calendar().events().patch(calendarId, eventId, toGoogle(event)).execute();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public void deleteEvent(String calendarId, String eventId) {
        try {
            calendar().events().delete(calendarId, eventId).execute();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public String createCalendar(String roomName) {
        try {
            com.google.api.services.calendar.model.Calendar cal =
                    new com.google.api.services.calendar.model.Calendar()
                            .setSummary(roomName)
                            .setDescription("Lich phong hop: " + roomName)
                            .setTimeZone(BusinessTime.ZONE.getId());
            return calendar().calendars().insert(cal).execute().getId();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public RemoteEventPage listChanges(String calendarId, String syncToken, Instant timeMin) {
        try {
            List<RemoteCalendarEvent> out = new java.util.ArrayList<>();
            String pageToken = null;
            String nextSyncToken = null;
            do {
                var req = calendar().events().list(calendarId)
                        .setSingleEvents(true)
                        .setShowDeleted(true)
                        .setPageToken(pageToken);
                if (syncToken != null) {
                    req.setSyncToken(syncToken);
                } else {
                    req.setTimeMin(new DateTime(timeMin.toEpochMilli()));
                }
                var page = req.execute();
                // Google omits "items" entirely when a page has no events
                for (Event e : page.getItems() == null ? List.<Event>of() : page.getItems()) {
                    boolean cancelled = "cancelled".equals(e.getStatus());
                    Instant start = toInstant(e.getStart());
                    Instant end = toInstant(e.getEnd());
                    String creator = e.getCreator() != null ? e.getCreator().getEmail() : null;
                    out.add(new RemoteCalendarEvent(e.getId(), e.getSummary(), e.getDescription(),
                            start, end, cancelled, creator));
                }
                pageToken = page.getNextPageToken();
                if (page.getNextSyncToken() != null) {
                    nextSyncToken = page.getNextSyncToken();
                }
            } while (pageToken != null);
            return new RemoteEventPage(out, nextSyncToken);
        } catch (IOException e) {
            if (e instanceof GoogleJsonResponseException gje && gje.getStatusCode() == 410) {
                throw new SyncTokenExpiredException("sync token expired for calendar " + calendarId);
            }
            throw translate(e);
        }
    }

    /** Timed events only; all-day events (date without dateTime) yield null and are skipped. */
    private Instant toInstant(EventDateTime t) {
        if (t == null || t.getDateTime() == null) {
            return null;
        }
        return Instant.ofEpochMilli(t.getDateTime().getValue());
    }

    private Event toGoogle(CalendarEvent event) {
        return CalendarEventMapper.toGoogle(event);
    }

    private RuntimeException translate(IOException e) {
        if (e instanceof GoogleJsonResponseException gje
                && (gje.getStatusCode() == 404 || gje.getStatusCode() == 410)) {
            return new CalendarEventNotFoundException(gje.getMessage());
        }
        return new IllegalStateException("Google Calendar call failed: " + e.getMessage(), e);
    }
}
