package pxt.dki.calendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pxt.dki.integrations.GoogleOAuthProperties;

import java.io.IOException;

/**
 * Pushes event copies into a user's primary calendar with their own OAuth credentials. The
 * Calendar client is built per call (one shared transport): at this scale one token-refresh
 * roundtrip per operation is acceptable, and no per-user state is cached.
 */
@Component
@ConditionalOnProperty(name = "app.google-oauth.enabled", havingValue = "true")
public class PersonalCalendarClientImpl implements PersonalCalendarClient {

    private static final String PRIMARY = "primary";

    private final GoogleOAuthProperties properties;
    private volatile HttpTransport transport;

    public PersonalCalendarClientImpl(GoogleOAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public String createEvent(String refreshToken, CalendarEvent event) {
        try {
            return calendar(refreshToken).events()
                    .insert(PRIMARY, CalendarEventMapper.toGoogle(event))
                    .execute().getId();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public void updateEvent(String refreshToken, String eventId, CalendarEvent event) {
        try {
            calendar(refreshToken).events()
                    .patch(PRIMARY, eventId, CalendarEventMapper.toGoogle(event))
                    .execute();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public void deleteEvent(String refreshToken, String eventId) {
        try {
            calendar(refreshToken).events().delete(PRIMARY, eventId).execute();
        } catch (IOException e) {
            throw translate(e);
        }
    }

    private Calendar calendar(String refreshToken) {
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(properties.clientId())
                .setClientSecret(properties.clientSecret())
                .setRefreshToken(refreshToken)
                .build();
        return new Calendar.Builder(transport(), GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("dki-room-booking")
                .build();
    }

    private HttpTransport transport() {
        HttpTransport local = transport;
        if (local == null) {
            synchronized (this) {
                if (transport == null) {
                    try {
                        transport = GoogleNetHttpTransport.newTrustedTransport();
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not initialize Google HTTP transport", e);
                    }
                }
                local = transport;
            }
        }
        return local;
    }

    private RuntimeException translate(IOException e) {
        if (e instanceof GoogleJsonResponseException gje
                && (gje.getStatusCode() == 404 || gje.getStatusCode() == 410)) {
            return new CalendarEventNotFoundException(gje.getMessage());
        }
        // token refresh failures bubble up as IOException with invalid_grant in the message chain
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("invalid_grant")) {
                return new GoogleAuthRevokedException("Google grant revoked");
            }
        }
        return new IllegalStateException("Google Calendar call failed: " + e.getMessage(), e);
    }
}
