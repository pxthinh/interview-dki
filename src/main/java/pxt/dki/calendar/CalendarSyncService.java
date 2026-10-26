package pxt.dki.calendar;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.booking.Booking;
import pxt.dki.booking.CalendarSyncPort;

import java.time.Clock;

/**
 * Writes outbox rows inside the caller's booking transaction (MANDATORY propagation), so the
 * calendar operation commits atomically with the booking or not at all.
 */
@Service
public class CalendarSyncService implements CalendarSyncPort {

    private final CalendarOutboxRepository outboxRepository;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarSyncService(CalendarOutboxRepository outboxRepository,
                               CalendarProperties properties, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Booking booking, OutboxAction action) {
        if (!properties.enabled() || booking.getRoom().getGoogleCalendarId() == null) {
            return;
        }
        CalendarOutbox row = new CalendarOutbox();
        row.setBookingId(booking.getId());
        row.setAction(action);
        row.setStatus(OutboxStatus.PENDING);
        row.setNextAttemptAt(clock.instant());
        outboxRepository.save(row);
    }
}
