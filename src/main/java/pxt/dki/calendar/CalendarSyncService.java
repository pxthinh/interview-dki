package pxt.dki.calendar;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.CalendarSyncPort;
import pxt.dki.integrations.GoogleAccountStatus;
import pxt.dki.integrations.GoogleOAuthProperties;
import pxt.dki.integrations.UserGoogleAccount;
import pxt.dki.integrations.UserGoogleAccountRepository;
import pxt.dki.user.User;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes outbox rows inside the caller's booking transaction (MANDATORY propagation), so the
 * calendar operation commits atomically with the booking or not at all.
 *
 * Rows are written whenever the capability is configured (env), even while an admin has paused
 * sync at runtime: the pause only stops the processor, so nothing done during the pause is lost.
 *
 * Personal rows (target_user_id set) are reconcile TRIGGERS: the processor decides from the
 * current state whether the user's copy must be created, patched or deleted, so their action
 * column is a label for the admin view, not an instruction.
 */
@Service
public class CalendarSyncService implements CalendarSyncPort {

    private final CalendarOutboxRepository outboxRepository;
    private final CalendarProperties properties;
    private final Clock clock;
    private final GoogleOAuthProperties oauthProperties;
    private final UserGoogleAccountRepository googleAccounts;
    private final BookingRepository bookingRepository;
    private final BookingUserEventRepository bookingUserEvents;
    private final PersonalCopyPolicy policy;

    public CalendarSyncService(CalendarOutboxRepository outboxRepository,
                               CalendarProperties properties, Clock clock,
                               GoogleOAuthProperties oauthProperties,
                               UserGoogleAccountRepository googleAccounts,
                               BookingRepository bookingRepository,
                               BookingUserEventRepository bookingUserEvents,
                               PersonalCopyPolicy policy) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.clock = clock;
        this.oauthProperties = oauthProperties;
        this.googleAccounts = googleAccounts;
        this.bookingRepository = bookingRepository;
        this.bookingUserEvents = bookingUserEvents;
        this.policy = policy;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Booking booking, OutboxAction action) {
        if (roomEnabled(booking)) {
            save(booking.getId(), action, null);
        }
        enqueuePersonal(booking, action);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueUpdate(Booking booking, Collection<User> added, Collection<User> removed) {
        if (roomEnabled(booking)) {
            save(booking.getId(), OutboxAction.UPDATE, null);
        }
        if (!oauthProperties.enabled()) {
            return;
        }
        Set<UUID> addedIds = new HashSet<>(connectedOf(added));
        for (UUID userId : addedIds) {
            save(booking.getId(), OutboxAction.CREATE, userId);
        }
        for (UUID userId : connectedOf(removed)) {
            save(booking.getId(), OutboxAction.CANCEL, userId);
        }
        for (UUID userId : connectedRecipients(booking)) {
            if (!addedIds.contains(userId)) {
                save(booking.getId(), OutboxAction.UPDATE, userId);
            }
        }
    }

    /**
     * Personal rows only, for changes that must NOT reach the room calendar again - e.g. the
     * import job applying an edit that came from the room calendar itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePersonal(Booking booking, OutboxAction action) {
        for (UUID userId : connectedRecipients(booking)) {
            save(booking.getId(), action, userId);
        }
    }

    /**
     * After a user (re)connects: one reconcile row per upcoming meeting they take part in, plus
     * one per existing copy that should no longer exist (cancelled / no-show / removed).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueResync(UUID userId) {
        if (!oauthProperties.enabled()) {
            return;
        }
        List<Booking> upcoming = bookingRepository.findActiveUpcomingFor(userId, clock.instant());
        Set<UUID> mapped = bookingUserEvents.findByUserId(userId).stream()
                .map(BookingUserEvent::getBookingId).collect(Collectors.toSet());
        Set<UUID> upcomingIds = new HashSet<>();
        for (Booking b : upcoming) {
            upcomingIds.add(b.getId());
            if (policy.wants(b, userId) || mapped.contains(b.getId())) {
                save(b.getId(), mapped.contains(b.getId()) ? OutboxAction.UPDATE : OutboxAction.CREATE, userId);
            }
        }
        List<UUID> others = mapped.stream().filter(id -> !upcomingIds.contains(id)).toList();
        for (Booking b : bookingRepository.findAllById(others)) {
            if (!policy.wants(b, userId)) {
                save(b.getId(), OutboxAction.CANCEL, userId);
            }
        }
    }

    private boolean roomEnabled(Booking booking) {
        return properties.enabled() && booking.getRoom().getGoogleCalendarId() != null;
    }

    private List<UUID> connectedRecipients(Booking booking) {
        if (!oauthProperties.enabled()) {
            return List.of();
        }
        var recipients = new LinkedHashSet<User>(booking.getParticipants());
        recipients.add(booking.getUser());
        return connectedOf(recipients);
    }

    /** CONNECTED Google accounts of active users (a deactivated user never gets copies). */
    private List<UUID> connectedOf(Collection<User> users) {
        if (users.isEmpty() || !oauthProperties.enabled()) {
            return List.of();
        }
        var ids = users.stream().filter(User::isActive).map(User::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return googleAccounts.findByUserIdInAndStatus(ids, GoogleAccountStatus.CONNECTED)
                .stream().map(UserGoogleAccount::getUserId).toList();
    }

    /** Any row in these states means the booking was already handed to the outbox; FAILED rows are retried per row by the admin. */
    private static final List<OutboxStatus> ALREADY_QUEUED = List.of(OutboxStatus.PENDING, OutboxStatus.DONE, OutboxStatus.FAILED);

    /**
     * Enqueues all existing ACTIVE upcoming bookings for room calendar sync and connected personal sync.
     * Returns the count of enqueued outbox records.
     */
    @Transactional
    public int enqueueAllExisting() {
        Instant now = clock.instant();
        int count = 0;
        if (properties.enabled()) {
            List<Booking> roomBookings = bookingRepository.findActiveUpcomingWithRoomCalendar(now);
            for (Booking b : roomBookings) {
                if (b.getGoogleEventId() == null && !outboxRepository.existsByBookingIdAndTargetUserIdIsNullAndStatusIn(
                        b.getId(), ALREADY_QUEUED)) {
                    save(b.getId(), OutboxAction.CREATE, null);
                    count++;
                }
            }
        }
        if (oauthProperties.enabled()) {
            List<UserGoogleAccount> connected = googleAccounts.findByStatus(GoogleAccountStatus.CONNECTED);
            for (UserGoogleAccount acc : connected) {
                List<Booking> upcoming = bookingRepository.findActiveUpcomingFor(acc.getUserId(), now);
                for (Booking b : upcoming) {
                    if (policy.wants(b, acc.getUserId())) {
                        if (!outboxRepository.existsByBookingIdAndTargetUserIdAndStatusIn(
                                b.getId(), acc.getUserId(), ALREADY_QUEUED)) {
                            save(b.getId(), OutboxAction.CREATE, acc.getUserId());
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private void save(UUID bookingId, OutboxAction action, UUID targetUserId) {
        CalendarOutbox row = new CalendarOutbox();
        row.setBookingId(bookingId);
        row.setAction(action);
        row.setStatus(OutboxStatus.PENDING);
        row.setNextAttemptAt(clock.instant());
        row.setTargetUserId(targetUserId);
        outboxRepository.save(row);
    }
}
