package pxt.dki.calendar;

import org.springframework.stereotype.Component;
import pxt.dki.booking.Booking;
import pxt.dki.booking.CalendarSyncPort;

/** Placeholder until the outbox-based CalendarSyncService lands (Task 9). */
@Component
public class NoOpCalendarSync implements CalendarSyncPort {

    @Override
    public void enqueue(Booking booking, OutboxAction action) {
        // intentionally empty
    }
}
