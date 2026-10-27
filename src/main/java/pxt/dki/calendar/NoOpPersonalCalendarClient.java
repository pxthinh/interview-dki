package pxt.dki.calendar;

/**
 * Selected by {@link CalendarClientsConfig} when personal OAuth is disabled; personal rows are
 * not even claimed then, so this is only a safe placeholder.
 */
public class NoOpPersonalCalendarClient implements PersonalCalendarClient {

    @Override
    public String createEvent(String refreshToken, String eventId, CalendarEvent event) {
        return eventId;
    }

    @Override
    public void updateEvent(String refreshToken, String eventId, CalendarEvent event) {
    }

    @Override
    public void deleteEvent(String refreshToken, String eventId) {
    }
}
