package pxt.dki.calendar;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Active when personal OAuth is disabled; no personal rows are enqueued then anyway. */
@Component
@ConditionalOnProperty(name = "app.google-oauth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpPersonalCalendarClient implements PersonalCalendarClient {

    @Override
    public String createEvent(String refreshToken, CalendarEvent event) {
        return null;
    }

    @Override
    public void updateEvent(String refreshToken, String eventId, CalendarEvent event) {
    }

    @Override
    public void deleteEvent(String refreshToken, String eventId) {
    }
}
