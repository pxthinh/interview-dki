package pxt.dki.calendar;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.BookingStatus;
import pxt.dki.booking.CalendarSyncPort;
import pxt.dki.integrations.GoogleAccountStatus;
import pxt.dki.integrations.GoogleOAuthProperties;
import pxt.dki.integrations.UserGoogleAccount;
import pxt.dki.integrations.UserGoogleAccountRepository;
import pxt.dki.settings.SettingsService;
import pxt.dki.user.User;

import java.time.Clock;
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
 * Personal rows (target_user_id set) are reconcile TRIGGERS: the processor decides from the
 * current state whether the user's copy must be created, patched or deleted, so their action
 * column is a label for the admin view, not an instruction.
 */
@Service
public class CalendarSyncService implements CalendarSyncPort {

    private final CalendarOutboxRepository outboxRepository;
    private final CalendarProperties properties;
    private final Clock clock;
    private final SettingsService settings;
    private final GoogleOAuthProperties oauthProperties;
    private final UserGoogleAccountRepository googleAccounts;
    private final BookingRepository bookingRepository;
    private final BookingUserEventRepository bookingUserEvents;

    public CalendarSyncService(CalendarOutboxRepository outboxRepository,
                               CalendarProperties properties, Clock clock,
                               SettingsService settings,
                               GoogleOAuthProperties oauthProperties,
                               UserGoogleAccountRepository googleAccounts,
                               BookingRepository bookingRepository,
                               BookingUserEventRepository bookingUserEvents) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.clock = clock;
        this.settings = settings;
        this.oauthProperties = oauthProperties;
        this.googleAccounts = googleAccounts;
        this.bookingRepository = bookingRepository;
        this.bookingUserEvents = bookingUserEvents;
    }

    /** The single rule personal copies converge to: the meeting is on and the user takes part. */
    static boolean wantsPersonalCopy(Booking booking, UUID userId) {
        return booking.getStatus() == BookingStatus.ACTIVE
                && (booking.getUser().getId().equals(userId)
                || booking.getParticipants().stream().anyMatch(u -> u.getId().equals(userId)));
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
        if (!personalEnabled()) {
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
        if (!personalEnabled()) {
            return;
        }
        List<Booking> upcoming = bookingRepository.findActiveUpcomingFor(userId, clock.instant());
        Set<UUID> mapped = bookingUserEvents.findByUserId(userId).stream()
                .map(BookingUserEvent::getBookingId).collect(Collectors.toSet());
        Set<UUID> upcomingIds = new HashSet<>();
        for (Booking b : upcoming) {
            upcomingIds.add(b.getId());
            save(b.getId(), mapped.contains(b.getId()) ? OutboxAction.UPDATE : OutboxAction.CREATE, userId);
        }
        List<UUID> others = mapped.stream().filter(id -> !upcomingIds.contains(id)).toList();
        for (Booking b : bookingRepository.findAllById(others)) {
            if (!wantsPersonalCopy(b, userId)) {
                save(b.getId(), OutboxAction.CANCEL, userId);
            }
        }
    }

    private boolean roomEnabled(Booking booking) {
        return properties.enabled() && settings.calendarSyncEnabled()
                && booking.getRoom().getGoogleCalendarId() != null;
    }

    private boolean personalEnabled() {
        return oauthProperties.enabled() && settings.calendarSyncEnabled();
    }

    private List<UUID> connectedRecipients(Booking booking) {
        if (!personalEnabled()) {
            return List.of();
        }
        var recipients = new LinkedHashSet<User>(booking.getParticipants());
        recipients.add(booking.getUser());
        return connectedOf(recipients);
    }

    private List<UUID> connectedOf(Collection<User> users) {
        if (users.isEmpty() || !personalEnabled()) {
            return List.of();
        }
        var ids = users.stream().map(User::getId).toList();
        return googleAccounts.findByUserIdInAndStatus(ids, GoogleAccountStatus.CONNECTED)
                .stream().map(UserGoogleAccount::getUserId).toList();
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
