package pxt.dki.calendar;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory personal-calendar double. Keyed by refresh token = one "calendar" per user. */
public class FakePersonalCalendarClient implements PersonalCalendarClient {

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final Map<String, CalendarEvent> events = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    /** Refresh token that simulates a revoked grant. */
    public volatile String revokedToken;

    private void checkRevoked(String refreshToken) {
        if (refreshToken != null && refreshToken.equals(revokedToken)) {
            throw new GoogleAuthRevokedException("invalid_grant");
        }
    }

    @Override
    public String createEvent(String refreshToken, CalendarEvent event) {
        checkRevoked(refreshToken);
        String id = "pev-" + seq.incrementAndGet();
        events.put(refreshToken + ":" + id, event);
        calls.add("create:" + refreshToken + ":" + event.summary());
        return id;
    }

    @Override
    public void updateEvent(String refreshToken, String eventId, CalendarEvent event) {
        checkRevoked(refreshToken);
        if (!events.containsKey(refreshToken + ":" + eventId)) {
            throw new CalendarEventNotFoundException(eventId);
        }
        events.put(refreshToken + ":" + eventId, event);
        calls.add("update:" + refreshToken + ":" + eventId);
    }

    @Override
    public void deleteEvent(String refreshToken, String eventId) {
        checkRevoked(refreshToken);
        if (events.remove(refreshToken + ":" + eventId) == null) {
            throw new CalendarEventNotFoundException(eventId);
        }
        calls.add("delete:" + refreshToken + ":" + eventId);
    }
}
