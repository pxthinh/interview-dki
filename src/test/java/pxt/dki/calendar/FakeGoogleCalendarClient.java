package pxt.dki.calendar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory stand-in for Google: records calls, can fail on demand. */
public class FakeGoogleCalendarClient implements GoogleCalendarClient {

    public final List<String> calls = new ArrayList<>();
    public final AtomicBoolean alwaysFail = new AtomicBoolean(false);
    public final AtomicBoolean notFoundOnDelete = new AtomicBoolean(false);
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public synchronized String createEvent(String calendarId, CalendarEvent event) {
        failIfRequested();
        calls.add("create:" + calendarId);
        return "evt-" + counter.incrementAndGet();
    }

    @Override
    public synchronized void updateEvent(String calendarId, String eventId, CalendarEvent event) {
        failIfRequested();
        calls.add("update:" + calendarId + ":" + eventId);
    }

    @Override
    public synchronized void deleteEvent(String calendarId, String eventId) {
        if (notFoundOnDelete.get()) {
            calls.add("delete-404:" + calendarId + ":" + eventId);
            throw new CalendarEventNotFoundException("gone");
        }
        failIfRequested();
        calls.add("delete:" + calendarId + ":" + eventId);
    }

    @Override
    public synchronized String createCalendar(String roomName) {
        failIfRequested();
        calls.add("create-calendar:" + roomName);
        return "cal-" + roomName.toLowerCase();
    }

    private void failIfRequested() {
        if (alwaysFail.get()) {
            throw new IllegalStateException("simulated google outage");
        }
    }

    public synchronized void reset() {
        calls.clear();
        alwaysFail.set(false);
        notFoundOnDelete.set(false);
    }
}
