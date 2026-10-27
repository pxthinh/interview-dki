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
    private final pxt.dki.settings.SettingsService settings;
    private final pxt.dki.integrations.GoogleOAuthProperties oauthProperties;
    private final pxt.dki.integrations.UserGoogleAccountRepository googleAccounts;

    public CalendarSyncService(CalendarOutboxRepository outboxRepository,
                               CalendarProperties properties, Clock clock,
                               pxt.dki.settings.SettingsService settings,
                               pxt.dki.integrations.GoogleOAuthProperties oauthProperties,
                               pxt.dki.integrations.UserGoogleAccountRepository googleAccounts) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.clock = clock;
        this.settings = settings;
        this.oauthProperties = oauthProperties;
        this.googleAccounts = googleAccounts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Booking booking, OutboxAction action) {
        if (properties.enabled() && settings.calendarSyncEnabled()
                && booking.getRoom().getGoogleCalendarId() != null) {
            save(booking, action, null);
        }
        // personal copies: organizer + participants with a CONNECTED Google account;
        // deliberately independent of the room having a calendar
        for (java.util.UUID userId : connectedRecipients(booking)) {
            save(booking, action, userId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueUpdate(Booking booking, java.util.Collection<pxt.dki.user.User> added,
                              java.util.Collection<pxt.dki.user.User> removed) {
        if (properties.enabled() && settings.calendarSyncEnabled()
                && booking.getRoom().getGoogleCalendarId() != null) {
            save(booking, OutboxAction.UPDATE, null);
        }
        if (!personalEnabled()) {
            return;
        }
        var addedIds = new java.util.HashSet<>(connectedOf(added));
        for (java.util.UUID userId : addedIds) {
            save(booking, OutboxAction.CREATE, userId); // new copy, no UPDATE row for them
        }
        for (java.util.UUID userId : connectedOf(removed)) {
            save(booking, OutboxAction.CANCEL, userId);
        }
        for (java.util.UUID userId : connectedRecipients(booking)) {
            if (!addedIds.contains(userId)) {
                save(booking, OutboxAction.UPDATE, userId);
            }
        }
    }

    private boolean personalEnabled() {
        return oauthProperties.enabled() && settings.calendarSyncEnabled();
    }

    private java.util.List<java.util.UUID> connectedRecipients(Booking booking) {
        if (!personalEnabled()) {
            return java.util.List.of();
        }
        var recipients = new java.util.LinkedHashSet<pxt.dki.user.User>(booking.getParticipants());
        recipients.add(booking.getUser());
        return connectedOf(recipients);
    }

    private java.util.List<java.util.UUID> connectedOf(java.util.Collection<pxt.dki.user.User> users) {
        if (users.isEmpty() || !personalEnabled()) {
            return java.util.List.of();
        }
        var ids = users.stream().map(pxt.dki.user.User::getId).toList();
        return googleAccounts
                .findByUserIdInAndStatus(ids, pxt.dki.integrations.GoogleAccountStatus.CONNECTED)
                .stream().map(pxt.dki.integrations.UserGoogleAccount::getUserId).toList();
    }

    private void save(Booking booking, OutboxAction action, java.util.UUID targetUserId) {
        CalendarOutbox row = new CalendarOutbox();
        row.setBookingId(booking.getId());
        row.setAction(action);
        row.setStatus(OutboxStatus.PENDING);
        row.setNextAttemptAt(clock.instant());
        row.setTargetUserId(targetUserId);
        outboxRepository.save(row);
    }
}
