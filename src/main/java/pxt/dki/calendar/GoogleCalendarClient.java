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

    /**
     * Creates a dedicated Google Calendar for a room (owned by the service account,
     * Asia/Ho_Chi_Minh timezone). @return the new calendar id, or null when unsupported.
     */
    String createCalendar(String roomName);
}
