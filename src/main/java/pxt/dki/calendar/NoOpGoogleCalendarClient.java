package pxt.dki.calendar;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Active when calendar sync is disabled; nothing reaches it because enqueue() is a no-op too. */
@Component
@ConditionalOnProperty(name = "app.calendar.enabled", havingValue = "false", matchIfMissing = true)
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
}
