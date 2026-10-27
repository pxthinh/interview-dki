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
    private final pxt.dki.integrations.GoogleOAuthProperties oauthProperties;
    private final PersonalCalendarClient personalClient;
    private final pxt.dki.integrations.UserGoogleAccountRepository googleAccounts;
    private final BookingUserEventRepository bookingUserEvents;
    private final pxt.dki.integrations.TokenCipher tokenCipher;

    public CalendarOutboxProcessor(CalendarOutboxRepository outboxRepository,
                                   BookingRepository bookingRepository,
                                   GoogleCalendarClient client,
                                   CalendarProperties properties,
                                   Clock clock,
                                   TransactionTemplate tx,
                                   pxt.dki.settings.SettingsService settings,
                                   pxt.dki.integrations.GoogleOAuthProperties oauthProperties,
                                   PersonalCalendarClient personalClient,
                                   pxt.dki.integrations.UserGoogleAccountRepository googleAccounts,
                                   BookingUserEventRepository bookingUserEvents,
                                   pxt.dki.integrations.TokenCipher tokenCipher) {
        this.outboxRepository = outboxRepository;
        this.bookingRepository = bookingRepository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        this.settings = settings;
        this.oauthProperties = oauthProperties;
        this.personalClient = personalClient;
        this.googleAccounts = googleAccounts;
        this.bookingUserEvents = bookingUserEvents;
        this.tokenCipher = tokenCipher;
    }

    /**
     * One unit of Google work, fully materialized inside the claim transaction. targetUserId and
     * refreshToken are set for personal-calendar rows only; the token is decrypted here and must
     * never be logged.
     */
    private record WorkItem(long outboxId, OutboxAction action, java.util.UUID bookingId,
                            String calendarId, String googleEventId, CalendarEvent event,
                            java.util.UUID targetUserId, String refreshToken, String googleEmail) {

        static WorkItem room(long outboxId, OutboxAction action, java.util.UUID bookingId,
                             String calendarId, String googleEventId, CalendarEvent event) {
            return new WorkItem(outboxId, action, bookingId, calendarId, googleEventId, event,
                    null, null, null);
        }
    }

    @Scheduled(fixedDelayString = "${app.calendar.poll-interval-ms}")
    public void poll() {
        // admin toggle pauses pushing too: PENDING rows wait and resume when re-enabled.
        // Personal-only mode (SA sync off, OAuth on) must still poll.
        if ((!properties.enabled() && !oauthProperties.enabled()) || !settings.calendarSyncEnabled()) {
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
        List<CalendarOutbox> batch = outboxRepository.lockPendingBatch(now, properties.batchSize(),
                properties.enabled(), oauthProperties.enabled());
        List<WorkItem> work = new ArrayList<>();
        java.util.Set<BookingUserEventId> personalSeen = new java.util.HashSet<>();
        for (CalendarOutbox item : batch) {
            Booking booking = bookingRepository.findById(item.getBookingId()).orElse(null);
            if (booking == null) {
                done(item, "booking deleted");
                continue;
            }
            if (item.getTargetUserId() != null) {
                // one reconcile per (booking, user) per batch: later rows would compute the same
                // target state, and two CREATEs claimed together would push two copies
                if (!personalSeen.add(new BookingUserEventId(booking.getId(), item.getTargetUserId()))) {
                    done(item, "coalesced");
                    continue;
                }
                claimPersonal(item, booking).ifPresent(work::add);
                continue;
            }
            if (booking.getStatus() == BookingStatus.CANCELLED && item.getAction() != OutboxAction.CANCEL) {
                // Cancelled before this CREATE/UPDATE ran; nothing to push.
                done(item, "booking cancelled");
                continue;
            }
            String calendarId = booking.getRoom().getGoogleCalendarId();
            if (calendarId == null) {
                done(item, "room has no calendar");
                continue;
            }
            switch (item.getAction()) {
                case CREATE -> work.add(WorkItem.room(item.getId(), OutboxAction.CREATE, booking.getId(),
                        calendarId, null, CalendarEvent.from(booking, properties.inviteBooker())));
                case UPDATE -> {
                    if (booking.getGoogleEventId() == null) {
                        // CREATE has not succeeded yet; retry later without burning an attempt.
                        item.setNextAttemptAt(now.plus(Duration.ofSeconds(30)));
                    } else {
                        work.add(WorkItem.room(item.getId(), OutboxAction.UPDATE, booking.getId(),
                                calendarId, booking.getGoogleEventId(),
                                CalendarEvent.from(booking, properties.inviteBooker())));
                    }
                }
                case CANCEL -> {
                    if (booking.getGoogleEventId() == null) {
                        done(item, "no event to delete");
                    } else {
                        work.add(WorkItem.room(item.getId(), OutboxAction.CANCEL, booking.getId(),
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

    /**
     * Personal-calendar branch of the claim. The row only triggers a reconcile: what to do is
     * decided from the current state - desired (meeting ACTIVE and the user takes part) versus
     * actual (a mapping to an existing copy) - never from the row's action label. This keeps
     * remove/re-add sequences, rows arriving out of order, and reconnects convergent.
     */
    private java.util.Optional<WorkItem> claimPersonal(CalendarOutbox item, Booking booking) {
        java.util.UUID userId = item.getTargetUserId();
        var account = googleAccounts.findById(userId).orElse(null);
        if (account == null || account.getStatus() != pxt.dki.integrations.GoogleAccountStatus.CONNECTED) {
            done(item, "user not connected");
            return java.util.Optional.empty();
        }
        var mapping = bookingUserEvents.findById(new BookingUserEventId(booking.getId(), userId)).orElse(null);
        boolean desired = CalendarSyncService.wantsPersonalCopy(booking, userId);
        if (!desired && mapping == null) {
            done(item, "nothing to sync");
            return java.util.Optional.empty();
        }
        String refreshToken;
        try {
            refreshToken = tokenCipher.decrypt(account.getRefreshTokenEnc());
        } catch (RuntimeException e) {
            // wrong/rotated key or corrupt value: isolate this user, never stall the batch
            log.warn("Stored Google token of user {} is unreadable; marking connection EXPIRED", userId);
            account.setStatus(pxt.dki.integrations.GoogleAccountStatus.EXPIRED);
            googleAccounts.save(account);
            fail(item, "stored Google token unreadable - user must reconnect");
            return java.util.Optional.empty();
        }
        if (desired && mapping == null) {
            return java.util.Optional.of(new WorkItem(item.getId(), OutboxAction.CREATE, booking.getId(),
                    null, null, CalendarEvent.from(booking, false), userId, refreshToken,
                    account.getGoogleEmail()));
        }
        if (desired) {
            return java.util.Optional.of(new WorkItem(item.getId(), OutboxAction.UPDATE, booking.getId(),
                    null, mapping.getGoogleEventId(), CalendarEvent.from(booking, false), userId, refreshToken,
                    account.getGoogleEmail()));
        }
        return java.util.Optional.of(new WorkItem(item.getId(), OutboxAction.CANCEL, booking.getId(),
                null, mapping.getGoogleEventId(), null, userId, refreshToken, account.getGoogleEmail()));
    }

    /** Phases 2 + 3: Google call with no transaction open, then a short recording transaction. */
    private void callGoogleAndRecord(WorkItem w) {
        if (w.targetUserId() != null) {
            callPersonalAndRecord(w);
            return;
        }
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

    /** Personal copies: same three phases, with the user's own credentials. */
    private void callPersonalAndRecord(WorkItem w) {
        try {
            switch (w.action()) {
                case CREATE -> {
                    String eventId = personalClient.createEvent(w.refreshToken(), w.event());
                    tx.executeWithoutResult(s -> {
                        BookingUserEvent mapping = bookingUserEvents
                                .findById(new BookingUserEventId(w.bookingId(), w.targetUserId()))
                                .orElseGet(() -> {
                                    BookingUserEvent m = new BookingUserEvent();
                                    m.setBookingId(w.bookingId());
                                    m.setUserId(w.targetUserId());
                                    return m;
                                });
                        mapping.setGoogleEventId(eventId);
                        mapping.setGoogleEmail(w.googleEmail());
                        bookingUserEvents.save(mapping);
                        outboxRepository.findById(w.outboxId()).ifPresent(item -> done(item, null));
                    });
                }
                case UPDATE -> {
                    try {
                        personalClient.updateEvent(w.refreshToken(), w.googleEventId(), w.event());
                        tx.executeWithoutResult(s ->
                                outboxRepository.findById(w.outboxId()).ifPresent(item -> done(item, null)));
                    } catch (CalendarEventNotFoundException e) {
                        // the user deleted their copy: forget it; the next change re-creates it
                        tx.executeWithoutResult(s -> {
                            bookingUserEvents.deleteById(new BookingUserEventId(w.bookingId(), w.targetUserId()));
                            outboxRepository.findById(w.outboxId())
                                    .ifPresent(item -> done(item, "personal copy was deleted on Google"));
                        });
                    }
                }
                case CANCEL -> {
                    try {
                        personalClient.deleteEvent(w.refreshToken(), w.googleEventId());
                    } catch (CalendarEventNotFoundException e) {
                        // already gone from the personal calendar
                    }
                    tx.executeWithoutResult(s -> {
                        bookingUserEvents.deleteById(new BookingUserEventId(w.bookingId(), w.targetUserId()));
                        outboxRepository.findById(w.outboxId()).ifPresent(item -> done(item, null));
                    });
                }
            }
        } catch (GoogleAuthRevokedException e) {
            // terminal: mark the account, fail the row once, let every other item continue
            tx.executeWithoutResult(s -> {
                googleAccounts.findById(w.targetUserId()).ifPresent(a -> {
                    a.setStatus(pxt.dki.integrations.GoogleAccountStatus.EXPIRED);
                    googleAccounts.save(a);
                });
                outboxRepository.findById(w.outboxId())
                        .ifPresent(item -> fail(item, "Google token revoked - user must reconnect"));
            });
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
