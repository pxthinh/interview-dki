package pxt.dki.calendar;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory personal-calendar double, keyed by refresh token = one "calendar" per user. Mirrors
 * the Google behaviors the processor relies on: client-chosen event ids, a deleted event stays
 * as "cancelled" (so re-inserting its id conflicts and patching it with status=confirmed
 * restores it), and only ids that never existed answer 404.
 */
public class FakePersonalCalendarClient implements PersonalCalendarClient {

    public final List<String> calls = new CopyOnWriteArrayList<>();
    /** live events: "token:eventId" -> payload */
    public final Map<String, CalendarEvent> events = new ConcurrentHashMap<>();
    /** deleted (cancelled) events: "token:eventId" */
    public final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** Refresh token that simulates a revoked grant. */
    public volatile String revokedToken;
    /** Runs right before the revoked-token failure is thrown (e.g. to simulate a reconnect in flight). */
    public volatile Runnable onRevoked;
    /** Every call fails with a generic (retryable) error. */
    public volatile boolean alwaysFail;

    public void reset() {
        calls.clear();
        events.clear();
        cancelled.clear();
        revokedToken = null;
        onRevoked = null;
        alwaysFail = false;
    }

    /** The user deletes their copy in Google Calendar. */
    public void userDeletes(String refreshToken, String eventId) {
        String key = refreshToken + ":" + eventId;
        if (events.remove(key) != null) {
            cancelled.add(key);
        }
    }

    private void check(String refreshToken) {
        if (alwaysFail) {
            throw new IllegalStateException("Google Calendar call failed: simulated outage");
        }
        if (refreshToken != null && refreshToken.equals(revokedToken)) {
            Runnable hook = onRevoked;
            if (hook != null) {
                hook.run();
            }
            throw new GoogleAuthRevokedException("invalid_grant");
        }
    }

    @Override
    public String createEvent(String refreshToken, String eventId, CalendarEvent event) {
        check(refreshToken);
        String key = refreshToken + ":" + eventId;
        if (events.containsKey(key) || cancelled.remove(key)) {
            events.put(key, event); // id already used: restore/overwrite instead of duplicating
            calls.add("restore:" + refreshToken + ":" + eventId);
        } else {
            events.put(key, event);
            calls.add("create:" + refreshToken + ":" + event.summary());
        }
        return eventId;
    }

    @Override
    public void updateEvent(String refreshToken, String eventId, CalendarEvent event) {
        check(refreshToken);
        String key = refreshToken + ":" + eventId;
        if (!events.containsKey(key) && !cancelled.remove(key)) {
            throw new CalendarEventNotFoundException(eventId);
        }
        events.put(key, event); // patch with status=confirmed also un-deletes
        calls.add("update:" + refreshToken + ":" + eventId);
    }

    @Override
    public void deleteEvent(String refreshToken, String eventId) {
        check(refreshToken);
        String key = refreshToken + ":" + eventId;
        if (events.remove(key) == null) {
            throw new CalendarEventNotFoundException(eventId);
        }
        cancelled.add(key);
        calls.add("delete:" + refreshToken + ":" + eventId);
    }
}
