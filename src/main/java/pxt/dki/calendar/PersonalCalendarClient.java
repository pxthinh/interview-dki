package pxt.dki.calendar;

/**
 * Event operations on ONE user's primary Google Calendar, authenticated by that user's OAuth
 * refresh token. update/delete throw {@link CalendarEventNotFoundException} on 404/410; a revoked
 * grant surfaces as {@link GoogleAuthRevokedException}.
 */
public interface PersonalCalendarClient {

    String createEvent(String refreshToken, CalendarEvent event);

    void updateEvent(String refreshToken, String eventId, CalendarEvent event);

    void deleteEvent(String refreshToken, String eventId);
}
