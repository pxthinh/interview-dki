package pxt.dki.calendar;

/**
 * Event operations on ONE user's primary Google Calendar, authenticated by that user's OAuth
 * refresh token. update/delete throw {@link CalendarEventNotFoundException} on 404/410; a revoked
 * grant surfaces as {@link GoogleAuthRevokedException}.
 */
public interface PersonalCalendarClient {

    /**
     * Creates the copy under the caller-chosen id. If that id already exists in the calendar
     * (a duplicate CREATE, or a copy the user deleted, which Google keeps as cancelled), the
     * existing event is restored and overwritten instead - so one (booking, user) never yields
     * two events. Returns the event id.
     */
    String createEvent(String refreshToken, String eventId, CalendarEvent event);

    /** Patches the copy; a copy the user had deleted comes back (status confirmed). */
    void updateEvent(String refreshToken, String eventId, CalendarEvent event);

    void deleteEvent(String refreshToken, String eventId);
}
