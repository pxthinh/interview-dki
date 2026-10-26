package pxt.dki.calendar;

/**
 * Thin client over the Google Calendar API. updateEvent/deleteEvent throw
 * {@link CalendarEventNotFoundException} when Google answers 404/410.
 */
public interface GoogleCalendarClient {

    /** @return the created Google event id */
    String createEvent(String calendarId, CalendarEvent event);

    void updateEvent(String calendarId, String eventId, CalendarEvent event);

    void deleteEvent(String calendarId, String eventId);
}
