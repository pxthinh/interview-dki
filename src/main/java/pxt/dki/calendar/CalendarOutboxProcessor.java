package pxt.dki.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.BookingStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes pending outbox rows to Google in three phases:
 *
 * <ol>
 *   <li><b>Claim</b> (short transaction): lock due PENDING rows with SKIP LOCKED, resolve
 *       everything that needs no Google call (cancelled bookings, missing calendar, ...) and
 *       put a lease on the rest so another instance will not pick them up.</li>
 *   <li><b>Call Google</b> outside any transaction: no DB connection is held across HTTP.</li>
 *   <li><b>Record</b> (short transaction per item): targeted updates only — the booking's
 *       google_event_id via a focused UPDATE, never a full-row write of a stale snapshot.</li>
 * </ol>
 *
 * Failures back off exponentially (30s * 2^(attempts-1)) up to maxAttempts, then the row is
 * parked as FAILED for an admin to inspect and retry. Transactions run through
 * {@link TransactionTemplate}, so the scheduled {@link #poll()} path behaves exactly like the
 * tests that call {@link #processBatch()} directly (no self-invocation proxy trap).
 */
@Component
public class CalendarOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(CalendarOutboxProcessor.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final CalendarOutboxRepository outboxRepository;
    private final BookingRepository bookingRepository;
    private final GoogleCalendarClient client;
    private final CalendarProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final pxt.dki.settings.SettingsService settings;

    public CalendarOutboxProcessor(CalendarOutboxRepository outboxRepository,
                                   BookingRepository bookingRepository,
                                   GoogleCalendarClient client,
                                   CalendarProperties properties,
                                   Clock clock,
                                   TransactionTemplate tx,
                                   pxt.dki.settings.SettingsService settings) {
        this.outboxRepository = outboxRepository;
        this.bookingRepository = bookingRepository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        this.settings = settings;
    }

    /** One unit of Google work, fully materialized inside the claim transaction. */
    private record WorkItem(long outboxId, OutboxAction action, java.util.UUID bookingId,
                            String calendarId, String googleEventId, CalendarEvent event) {
    }

    @Scheduled(fixedDelayString = "${app.calendar.poll-interval-ms}")
    public void poll() {
        // admin toggle pauses pushing too: PENDING rows wait and resume when re-enabled
        if (!properties.enabled() || !settings.calendarSyncEnabled()) {
            return;
        }
        int processed = processBatch();
        if (processed > 0) {
            log.info("Calendar outbox: processed {} item(s)", processed);
        }
    }

    public int processBatch() {
        List<WorkItem> work = tx.execute(status -> claimDue());
        if (work == null) {
            return 0;
        }
        work.forEach(this::callGoogleAndRecord);
        return work.size();
    }

    /** Phase 1: runs inside a transaction; returns only the items that need a Google call. */
    private List<WorkItem> claimDue() {
        Instant now = clock.instant();
        List<CalendarOutbox> batch = outboxRepository.lockPendingBatch(now, properties.batchSize());
        List<WorkItem> work = new ArrayList<>();
        for (CalendarOutbox item : batch) {
            Booking booking = bookingRepository.findById(item.getBookingId()).orElse(null);
            if (booking == null) {
                done(item, "booking deleted");
                continue;
            }
            String calendarId = booking.getRoom().getGoogleCalendarId();
            if (calendarId == null) {
                done(item, "room has no calendar");
                continue;
            }
            if (booking.getStatus() == BookingStatus.CANCELLED && item.getAction() != OutboxAction.CANCEL) {
                // Cancelled before this CREATE/UPDATE ran; nothing to push.
                done(item, "booking cancelled");
                continue;
            }
            switch (item.getAction()) {
                case CREATE -> work.add(new WorkItem(item.getId(), OutboxAction.CREATE, booking.getId(),
                        calendarId, null, CalendarEvent.from(booking, properties.inviteBooker())));
                case UPDATE -> {
                    if (booking.getGoogleEventId() == null) {
                        // CREATE has not succeeded yet; retry later without burning an attempt.
                        item.setNextAttemptAt(now.plus(Duration.ofSeconds(30)));
                    } else {
                        work.add(new WorkItem(item.getId(), OutboxAction.UPDATE, booking.getId(),
                                calendarId, booking.getGoogleEventId(),
                                CalendarEvent.from(booking, properties.inviteBooker())));
                    }
                }
                case CANCEL -> {
                    if (booking.getGoogleEventId() == null) {
                        done(item, "no event to delete");
                    } else {
                        work.add(new WorkItem(item.getId(), OutboxAction.CANCEL, booking.getId(),
                                calendarId, booking.getGoogleEventId(), null));
                    }
                }
            }
        }
        // Lease every row that leaves this transaction still PENDING, so a second instance
        // (or the next poll) does not double-process it while Google is being called.
        for (WorkItem w : work) {
            batch.stream().filter(b -> b.getId().equals(w.outboxId())).findFirst()
                    .ifPresent(b -> b.setNextAttemptAt(now.plus(CLAIM_LEASE)));
        }
        return work;
    }

    /** Phases 2 + 3: Google call with no transaction open, then a short recording transaction. */
    private void callGoogleAndRecord(WorkItem w) {
        try {
            switch (w.action()) {
                case CREATE -> {
                    String eventId = client.createEvent(w.calendarId(), w.event());
                    tx.executeWithoutResult(s -> {
                        bookingRepository.updateGoogleEventId(w.bookingId(), eventId);
                        outboxRepository.findById(w.outboxId()).ifPresent(item -> done(item, null));
                    });
                }
                case UPDATE -> {
                    try {
                        client.updateEvent(w.calendarId(), w.googleEventId(), w.event());
                        tx.executeWithoutResult(s ->
                                outboxRepository.findById(w.outboxId()).ifPresent(item -> done(item, null)));
                    } catch (CalendarEventNotFoundException e) {
                        tx.executeWithoutResult(s -> outboxRepository.findById(w.outboxId())
                                .ifPresent(item -> fail(item, "event vanished on Google: " + e.getMessage())));
                    }
                }
                case CANCEL -> {
                    try {
                        client.deleteEvent(w.calendarId(), w.googleEventId());
                    } catch (CalendarEventNotFoundException e) {
                        // already gone on Google's side: mission accomplished
                    }
                    tx.executeWithoutResult(s ->
                            outboxRepository.findById(w.outboxId()).ifPresent(item -> done(item, null)));
                }
            }
        } catch (Exception e) {
            tx.executeWithoutResult(s ->
                    outboxRepository.findById(w.outboxId()).ifPresent(item -> retryOrFail(item, e)));
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
