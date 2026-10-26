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

    /**
     * Incremental reverse sync. With a null {@code syncToken}: full listing of upcoming events
     * from {@code timeMin}. Otherwise only the changes since that token.
     *
     * @throws SyncTokenExpiredException when Google answers 410 for the token (full resync needed)
     */
    RemoteEventPage listChanges(String calendarId, String syncToken, java.time.Instant timeMin);
}
