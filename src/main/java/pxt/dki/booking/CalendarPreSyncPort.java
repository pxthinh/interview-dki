package pxt.dki.booking;

import java.util.UUID;

/**
 * Best-effort pre-emptive import of one room's Google Calendar, called right before a booking
 * write (outside its transaction). Closes the race window in which an event created directly
 * on the calendar has not been polled in yet. Never throws, bounded by a short timeout:
 * Google being slow or down must not block booking.
 */
public interface CalendarPreSyncPort {

    void preSync(UUID roomId);
}
