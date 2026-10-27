package pxt.dki.calendar;

/**
 * Selected by {@link CalendarClientsConfig} when calendar sync is disabled; nothing reaches it
 * because enqueue() writes no room rows then.
 */
public class NoOpGoogleCalendarClient implements GoogleCalendarClient {

    @Override
    public String createEvent(String calendarId, CalendarEvent event) {
        return null;
    }

    @Override
    public void updateEvent(String calendarId, String eventId, CalendarEvent event) {
        // intentionally empty
    }

    @Override
    public void deleteEvent(String calendarId, String eventId) {
        // intentionally empty
    }

    @Override
    public String createCalendar(String roomName) {
        return null;
    }

    @Override
    public RemoteEventPage listChanges(String calendarId, String syncToken, java.time.Instant timeMin) {
        return new RemoteEventPage(java.util.List.of(), syncToken);
    }
}
