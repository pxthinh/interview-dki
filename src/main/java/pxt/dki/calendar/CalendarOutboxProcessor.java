package pxt.dki.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.BookingStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls the outbox and pushes pending calendar operations to Google. Failures back off
 * exponentially (30s * 2^(attempts-1)) up to maxAttempts, then the row is parked as FAILED
 * for an admin to inspect and retry. Google is never called inside a booking transaction.
 */
@Component
public class CalendarOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(CalendarOutboxProcessor.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final CalendarOutboxRepository outboxRepository;
    private final BookingRepository bookingRepository;
    private final GoogleCalendarClient client;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarOutboxProcessor(CalendarOutboxRepository outboxRepository,
                                   BookingRepository bookingRepository,
                                   GoogleCalendarClient client,
                                   CalendarProperties properties,
                                   Clock clock) {
        this.outboxRepository = outboxRepository;
        this.bookingRepository = bookingRepository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.calendar.poll-interval-ms}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        int processed = processBatch();
        if (processed > 0) {
            log.info("Calendar outbox: processed {} item(s)", processed);
        }
    }

    @Transactional
    public int processBatch() {
        Instant now = clock.instant();
        List<CalendarOutbox> batch = outboxRepository.lockPendingBatch(now, properties.batchSize());
        batch.forEach(this::processOne);
        return batch.size();
    }

    private void processOne(CalendarOutbox item) {
        try {
            Booking booking = bookingRepository.findById(item.getBookingId()).orElse(null);
            if (booking == null) {
                done(item, "booking deleted");
                return;
            }
            String calendarId = booking.getRoom().getGoogleCalendarId();
            if (calendarId == null) {
                done(item, "room has no calendar");
                return;
            }
            if (booking.getStatus() == BookingStatus.CANCELLED && item.getAction() != OutboxAction.CANCEL) {
                // The booking was cancelled before this CREATE/UPDATE ran; nothing to push.
                done(item, "booking cancelled");
                return;
            }

            switch (item.getAction()) {
                case CREATE -> {
                    String eventId = client.createEvent(calendarId,
                            CalendarEvent.from(booking, properties.inviteBooker()));
                    booking.setGoogleEventId(eventId);
                    bookingRepository.save(booking);
                    done(item, null);
                }
                case UPDATE -> {
                    if (booking.getGoogleEventId() == null) {
                        // CREATE has not succeeded yet; retry later without burning an attempt.
                        item.setNextAttemptAt(clock.instant().plus(Duration.ofSeconds(30)));
                        outboxRepository.save(item);
                        return;
                    }
                    try {
                        client.updateEvent(calendarId, booking.getGoogleEventId(),
                                CalendarEvent.from(booking, properties.inviteBooker()));
                        done(item, null);
                    } catch (CalendarEventNotFoundException e) {
                        fail(item, "event vanished on Google: " + e.getMessage());
                    }
                }
                case CANCEL -> {
                    if (booking.getGoogleEventId() == null) {
                        done(item, "no event to delete");
                        return;
                    }
                    try {
                        client.deleteEvent(calendarId, booking.getGoogleEventId());
                    } catch (CalendarEventNotFoundException e) {
                        // already gone on Google's side: mission accomplished
                    }
                    done(item, null);
                }
            }
        } catch (Exception e) {
            retryOrFail(item, e);
        }
    }

    private void retryOrFail(CalendarOutbox item, Exception e) {
        int attempts = item.getAttempts() + 1;
        item.setAttempts(attempts);
        String error = e.toString();
        item.setLastError(error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error);
        if (attempts >= properties.maxAttempts()) {
            item.setStatus(OutboxStatus.FAILED);
            item.setProcessedAt(clock.instant());
            log.error("Calendar outbox item {} failed permanently after {} attempts", item.getId(), attempts, e);
        } else {
            item.setNextAttemptAt(clock.instant().plus(backoff(attempts)));
            log.warn("Calendar outbox item {} failed (attempt {}), will retry", item.getId(), attempts);
        }
        outboxRepository.save(item);
    }

    private void done(CalendarOutbox item, String note) {
        item.setStatus(OutboxStatus.DONE);
        item.setProcessedAt(clock.instant());
        if (note != null) {
            item.setLastError(note);
        }
        outboxRepository.save(item);
    }

    private void fail(CalendarOutbox item, String error) {
        item.setStatus(OutboxStatus.FAILED);
        item.setProcessedAt(clock.instant());
        item.setLastError(error);
        outboxRepository.save(item);
    }

    static Duration backoff(int attempts) {
        return BASE_BACKOFF.multipliedBy(1L << (attempts - 1));
    }
}
