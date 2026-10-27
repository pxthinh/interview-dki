package pxt.dki.booking;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.auth.AuthUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import pxt.dki.booking.dto.BookingResponse;
import pxt.dki.booking.dto.BookingSearchFilter;
import pxt.dki.booking.dto.CreateBookingRequest;
import pxt.dki.booking.dto.CreateRecurringBookingRequest;
import pxt.dki.booking.dto.RecurringBookingResponse;
import pxt.dki.booking.dto.RoomScheduleResponse;
import pxt.dki.booking.dto.UpdateBookingRequest;
import pxt.dki.room.dto.RoomResponse;
import pxt.dki.calendar.OutboxAction;
import pxt.dki.common.error.BookingConflictException;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.error.ConflictInfo;
import pxt.dki.common.error.ResourceNotFoundException;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.room.Room;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static pxt.dki.common.time.BusinessTime.ZONE;

@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RecurringSeriesRepository recurringSeriesRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final BookingRuleValidator validator;
    private final CalendarSyncPort calendarSync;
    private final NotificationService notifications;
    private final Clock clock;
    private final CheckinProperties checkin;
    private final pxt.dki.settings.SettingsService settings;

    public BookingService(BookingRepository bookingRepository,
                          RecurringSeriesRepository recurringSeriesRepository, RoomRepository roomRepository,
                          UserRepository userRepository, BookingRuleValidator validator,
                          CalendarSyncPort calendarSync, NotificationService notifications, Clock clock,
                          CheckinProperties checkin, pxt.dki.settings.SettingsService settings) {
        this.bookingRepository = bookingRepository;
        this.recurringSeriesRepository = recurringSeriesRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.validator = validator;
        this.calendarSync = calendarSync;
        this.notifications = notifications;
        this.clock = clock;
        this.checkin = checkin;
        this.settings = settings;
    }

    public BookingResponse create(AuthUser actor, CreateBookingRequest request) {
        // Row lock on the room: concurrent booking attempts for the same room queue up here,
        // so the overlap check below sees every committed and in-flight booking.
        Room room = roomRepository.findByIdForUpdate(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (!room.isActive()) {
            throw new BusinessRuleException("ROOM_INACTIVE", "This room is not available for booking");
        }

        Instant start = request.startTime().toInstant();
        Instant end = request.endTime().toInstant();
        validator.validateTimes(start, end);
        validator.validateCapacity(request.attendeeCount(), room.getCapacity());
        int bufferMinutes = room.isBufferEnabled() ? (int) BookingRuleValidator.BOOKING_GAP.toMinutes() : 0;
        ensureNoOverlap(room.getId(), start, end, bufferMinutes, null);

        User user = userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setUser(user);
        booking.setTitle(request.title());
        booking.setDescription(request.description());
        booking.setAttendeeCount(request.attendeeCount());
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setBufferMinutes(bufferMinutes);
        booking.setParticipants(resolveParticipants(request.participantIds(), user.getId()));
        bookingRepository.save(booking);

        calendarSync.enqueue(booking, OutboxAction.CREATE);
        var snapshot = NotificationService.BookingSnapshot.of(booking);
        notifications.bookingConfirmed(snapshot, user); // organizer always gets a confirmation
        notifications.bookingCreated(snapshot, List.copyOf(booking.getParticipants()));
        return BookingResponse.from(booking);
    }

    /**
     * Weekly series under the same room lock and rules as {@link #create}: one real booking per
     * week, conflicting occurrences skipped and reported (partial success). Everything commits
     * in one transaction; when no occurrence fits, the whole request fails and no series row
     * survives the rollback.
     */
    public RecurringBookingResponse createRecurring(AuthUser actor, CreateRecurringBookingRequest request) {
        Room room = roomRepository.findByIdForUpdate(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (!room.isActive()) {
            throw new BusinessRuleException("ROOM_INACTIVE", "This room is not available for booking");
        }

        Instant firstStart = request.startTime().toInstant();
        Instant firstEnd = request.endTime().toInstant();
        validator.validateTimes(firstStart, firstEnd);
        validator.validateCapacity(request.attendeeCount(), room.getCapacity());

        // occurrences repeat on the Vietnam wall clock (no DST, so +1 week is always 7 days)
        java.time.ZonedDateTime startZ = firstStart.atZone(ZONE);
        java.time.ZonedDateTime endZ = firstEnd.atZone(ZONE);
        int weeks = request.weeks();
        int maxWeeks = settings.maxRecurringWeeks();
        if (weeks > maxWeeks) {
            throw new BusinessRuleException("RECURRING_TOO_MANY_WEEKS",
                    "A series may span at most " + maxWeeks + " weeks");
        }
        LocalDate today = BusinessTime.today(clock);
        int maxDaysAhead = settings.maxDaysAhead();
        if (startZ.plusWeeks(weeks - 1L).toLocalDate().isAfter(today.plusDays(maxDaysAhead))) {
            throw new BusinessRuleException("TOO_FAR_AHEAD",
                    "The last occurrence must start within " + maxDaysAhead + " days");
        }

        User user = userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        var participants = resolveParticipants(request.participantIds(), user.getId());
        int bufferMinutes = room.isBufferEnabled() ? (int) BookingRuleValidator.BOOKING_GAP.toMinutes() : 0;

        RecurringSeries series = new RecurringSeries();
        series.setUserId(user.getId());
        recurringSeriesRepository.save(series);

        var created = new java.util.ArrayList<Booking>();
        var skipped = new java.util.ArrayList<RecurringBookingResponse.SkippedOccurrence>();
        for (int i = 0; i < weeks; i++) {
            Instant start = startZ.plusWeeks(i).toInstant();
            Instant end = endZ.plusWeeks(i).toInstant();
            try {
                ensureNoOverlap(room.getId(), start, end, bufferMinutes, null);
            } catch (BookingConflictException e) {
                skipped.add(new RecurringBookingResponse.SkippedOccurrence(
                        BusinessTime.toApi(start), BusinessTime.toApi(end), e.getConflict()));
                continue;
            }
            Booking booking = new Booking();
            booking.setRoom(room);
            booking.setUser(user);
            booking.setTitle(request.title());
            booking.setDescription(request.description());
            booking.setAttendeeCount(request.attendeeCount());
            booking.setStartTime(start);
            booking.setEndTime(end);
            booking.setStatus(BookingStatus.ACTIVE);
            booking.setBufferMinutes(bufferMinutes);
            booking.setSeriesId(series.getId());
            booking.setParticipants(new java.util.LinkedHashSet<>(participants));
            bookingRepository.save(booking);
            calendarSync.enqueue(booking, OutboxAction.CREATE);
            created.add(booking);
        }

        if (created.isEmpty()) {
            throw new BusinessRuleException("RECURRING_ALL_CONFLICT",
                    "Every occurrence conflicts with an existing booking");
        }

        var snapshot = NotificationService.BookingSnapshot.of(created.get(0));
        notifications.recurringCreated(snapshot, created.size(), user, List.copyOf(participants));
        return new RecurringBookingResponse(series.getId(),
                created.stream().map(BookingResponse::from).toList(), skipped);
    }

    /**
     * Cancels the remaining occurrences of a series (still ACTIVE and not finished). Finished
     * or individually cancelled occurrences stay as they were. Returns the occurrences this
     * call cancelled; an empty list means there was nothing left to cancel.
     */
    public List<BookingResponse> cancelSeries(AuthUser actor, UUID seriesId) {
        RecurringSeries series = recurringSeriesRepository.findById(seriesId)
                .orElseThrow(() -> new ResourceNotFoundException("Series not found"));
        if (!actor.isAdmin() && !series.getUserId().equals(actor.id())) {
            throw new AccessDeniedException("Not your series");
        }
        Instant now = clock.instant();
        List<Booking> remaining = bookingRepository
                .findBySeriesIdAndStatusAndEndTimeGreaterThan(seriesId, BookingStatus.ACTIVE, now);
        for (Booking booking : remaining) {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(now);
            bookingRepository.save(booking);
            calendarSync.enqueue(booking, OutboxAction.CANCEL);
        }
        if (!remaining.isEmpty()) {
            Booking first = remaining.get(0);
            var recipients = new java.util.LinkedHashSet<User>();
            remaining.forEach(b -> recipients.addAll(b.getParticipants()));
            recipients.add(first.getUser());
            notifications.seriesCancelled(NotificationService.BookingSnapshot.of(first),
                    remaining.size(), recipients);
        }
        return remaining.stream().map(BookingResponse::from).toList();
    }

    /** Participants = existing active users, deduplicated, never the organizer themself. */
    private java.util.LinkedHashSet<User> resolveParticipants(List<UUID> ids, UUID organizerId) {
        java.util.LinkedHashSet<User> out = new java.util.LinkedHashSet<>();
        if (ids == null) {
            return out;
        }
        for (UUID id : ids) {
            if (id == null || id.equals(organizerId)) {
                continue;
            }
            User u = userRepository.findById(id)
                    .filter(User::isActive)
                    .orElseThrow(() -> new BusinessRuleException("PARTICIPANT_NOT_FOUND",
                            "participantIds contains an unknown or inactive user: " + id));
            out.add(u);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public BookingResponse get(AuthUser actor, UUID id) {
        Booking booking = require(id);
        requireOwnerOrAdmin(actor, booking);
        return BookingResponse.from(booking);
    }

    public BookingResponse update(AuthUser actor, UUID id, UpdateBookingRequest request) {
        Booking booking = require(id);
        requireOwnerOrAdmin(actor, booking);
        if (booking.getStatus() != BookingStatus.ACTIVE) {
            throw new BusinessRuleException("BOOKING_NOT_ACTIVE", "Only active bookings can be changed");
        }
        if (!booking.getStartTime().isAfter(clock.instant())) {
            throw new BusinessRuleException("BOOKING_ALREADY_STARTED",
                    "A booking that has started can no longer be changed");
        }

        // Same lock as create: serialize against other writes on this room.
        Room room = roomRepository.findByIdForUpdate(booking.getRoom().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (!room.isActive()) {
            // "apply every create rule again": a deactivated room takes no new times either
            throw new BusinessRuleException("ROOM_INACTIVE", "This room is not available for booking");
        }

        Instant start = request.startTime().toInstant();
        Instant end = request.endTime().toInstant();
        validator.validateTimes(start, end);
        validator.validateCapacity(request.attendeeCount(), room.getCapacity());
        int bufferMinutes = room.isBufferEnabled() ? (int) BookingRuleValidator.BOOKING_GAP.toMinutes() : 0;
        ensureNoOverlap(room.getId(), start, end, bufferMinutes, booking.getId());

        java.util.Set<User> before = new java.util.LinkedHashSet<>(booking.getParticipants());
        java.util.LinkedHashSet<User> after =
                resolveParticipants(request.participantIds(), booking.getUser().getId());
        var added = after.stream().filter(u -> !before.contains(u)).toList();
        var removed = before.stream().filter(u -> !after.contains(u)).toList();
        var kept = after.stream().filter(before::contains).toList();

        booking.setTitle(request.title());
        booking.setDescription(request.description());
        booking.setAttendeeCount(request.attendeeCount());
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setBufferMinutes(bufferMinutes);
        booking.setParticipants(after);
        bookingRepository.save(booking);

        calendarSync.enqueueUpdate(booking, added, removed);

        // notify: newly added -> invite; removed -> notice; kept -> updated details
        var snapshot = NotificationService.BookingSnapshot.of(booking);
        if (!added.isEmpty()) {
            notifications.bookingCreated(snapshot, added);
        }
        if (!removed.isEmpty()) {
            notifications.participantRemoved(snapshot, removed);
        }
        if (!kept.isEmpty()) {
            notifications.bookingUpdated(snapshot, kept);
        }
        return BookingResponse.from(booking);
    }

    public BookingResponse cancel(AuthUser actor, UUID id) {
        Booking booking = require(id);
        requireOwnerOrAdmin(actor, booking);
        if (booking.getStatus() != BookingStatus.ACTIVE) {
            throw new BusinessRuleException("BOOKING_NOT_ACTIVE", "This booking is already cancelled");
        }
        Instant now = clock.instant();
        if (!booking.getEndTime().isAfter(now)) {
            throw new BusinessRuleException("BOOKING_ALREADY_ENDED", "A finished booking cannot be cancelled");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        bookingRepository.save(booking);

        calendarSync.enqueue(booking, OutboxAction.CANCEL);
        notifications.bookingCancelled(NotificationService.BookingSnapshot.of(booking),
                List.copyOf(booking.getParticipants()));
        return BookingResponse.from(booking);
    }

    /**
     * Booker, any participant, or an admin confirms presence. The window opens
     * {@code earlyWindowMinutes} before start and stays open until the meeting ends while the
     * booking is ACTIVE — the no-show sweeper, not this endpoint, enforces the grace deadline.
     */
    public BookingResponse checkIn(AuthUser actor, UUID id) {
        Booking booking = require(id);
        boolean participant = booking.getParticipants().stream()
                .anyMatch(u -> u.getId().equals(actor.id()));
        if (!actor.isAdmin() && !booking.getUser().getId().equals(actor.id()) && !participant) {
            throw new AccessDeniedException("Not your booking");
        }
        if (booking.getCheckedInAt() != null) {
            return BookingResponse.from(booking); // idempotent
        }
        if (booking.getStatus() != BookingStatus.ACTIVE) {
            throw new BusinessRuleException("BOOKING_NOT_ACTIVE", "Only active bookings can be checked in");
        }
        Instant now = clock.instant();
        if (!booking.getEndTime().isAfter(now)) {
            throw new BusinessRuleException("BOOKING_ALREADY_ENDED", "This meeting has already ended");
        }
        if (now.isBefore(booking.getStartTime().minus(java.time.Duration.ofMinutes(checkin.earlyWindowMinutes())))) {
            throw new BusinessRuleException("CHECKIN_TOO_EARLY",
                    "Check-in opens " + checkin.earlyWindowMinutes() + " minutes before the meeting starts");
        }
        booking.setCheckedInAt(now);
        bookingRepository.save(booking);
        return BookingResponse.from(booking);
    }

    /**
     * Releases one unclaimed booking as NO_SHOW. Runs in its own transaction and re-checks the
     * state after reload, so a user cancelling or checking in concurrently simply wins; the
     * sweeper's loop survives a failure on one booking.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markNoShow(UUID id) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.ACTIVE || booking.getCheckedInAt() != null) {
            return;
        }
        Instant now = clock.instant();
        Instant deadline = booking.getStartTime().plus(java.time.Duration.ofMinutes(checkin.graceMinutes()));
        // deadline not reached yet, or the whole meeting is shorter than the grace period
        if (now.isBefore(deadline) || !booking.getEndTime().isAfter(deadline)) {
            return;
        }
        booking.setStatus(BookingStatus.NO_SHOW);
        // flush now: a concurrent cancel/check-in must surface as an optimistic-lock failure
        // BEFORE the email goes out — the async send cannot be rolled back with the tx
        bookingRepository.saveAndFlush(booking);

        calendarSync.enqueue(booking, OutboxAction.CANCEL);
        var recipients = new java.util.ArrayList<User>(booking.getParticipants());
        recipients.add(booking.getUser());
        notifications.bookingNoShow(NotificationService.BookingSnapshot.of(booking), recipients);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> myBookings(AuthUser actor, Instant from, Instant to, BookingStatus status,
                                            BookingRole role) {
        Instant effectiveFrom = from != null ? from : clock.instant();
        BookingStatus effectiveStatus = status != null ? status : BookingStatus.ACTIVE;
        Specification<Booking> spec = (root, q, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            var asOrganizer = cb.equal(root.get("user").get("id"), actor.id());
            if (role == BookingRole.ORGANIZER) {
                predicates.add(asOrganizer);
            } else {
                // participant (or no filter): join the invite list; distinct guards the OR case
                var participants = root.join("participants", jakarta.persistence.criteria.JoinType.LEFT);
                var asParticipant = cb.equal(participants.get("id"), actor.id());
                predicates.add(role == BookingRole.PARTICIPANT ? asParticipant
                        : cb.or(asOrganizer, asParticipant));
                q.distinct(true);
            }
            predicates.add(cb.equal(root.get("status"), effectiveStatus));
            predicates.add(cb.greaterThan(root.get("endTime"), effectiveFrom));
            if (to != null) {
                predicates.add(cb.lessThan(root.get("startTime"), to));
            }
            q.orderBy(cb.asc(root.get("startTime")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return bookingRepository.findAll(spec).stream().map(BookingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> search(BookingSearchFilter filter, Pageable pageable) {
        Specification<Booking> spec = (root, q, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (filter.roomId() != null) {
                predicates.add(cb.equal(root.get("room").get("id"), filter.roomId()));
            }
            if (filter.userId() != null) {
                predicates.add(cb.equal(root.get("user").get("id"), filter.userId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThan(root.get("endTime"), filter.from().toInstant()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("startTime"), filter.to().toInstant()));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return bookingRepository.findAll(spec, pageable).map(BookingResponse::from);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> roomSchedule(UUID roomId, LocalDate from, LocalDate to) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        LocalDate today = BusinessTime.today(clock);
        LocalDate effectiveFrom = from != null ? from : today;
        LocalDate effectiveTo = to != null ? to : effectiveFrom;
        validateRange(effectiveFrom, effectiveTo);
        Instant rangeStart = effectiveFrom.atStartOfDay(ZONE).toInstant();
        Instant rangeEnd = effectiveTo.plusDays(1).atStartOfDay(ZONE).toInstant();
        return bookingRepository.findActiveOverlapping(room.getId(), rangeStart, rangeEnd, null)
                .stream().map(BookingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RoomScheduleResponse> daySchedule(LocalDate date) {
        LocalDate effectiveDate = date != null ? date : BusinessTime.today(clock);
        return roomRepository.findByActiveTrueOrderByCapacityAscNameAsc().stream()
                .map(room -> new RoomScheduleResponse(
                        RoomResponse.from(room),
                        roomSchedule(room.getId(), effectiveDate, effectiveDate)))
                .toList();
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        if (to.isAfter(from.plusDays(30))) {
            throw new IllegalArgumentException("Schedule range is limited to 31 days");
        }
    }

    /** Room of a booking, for the controller's pre-sync; read-only lookup. */
    @Transactional(readOnly = true)
    public UUID roomIdOf(UUID bookingId) {
        return require(bookingId).getRoom().getId();
    }

    Booking require(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    void requireOwnerOrAdmin(AuthUser actor, Booking booking) {
        if (!actor.isAdmin() && !booking.getUser().getId().equals(actor.id())) {
            throw new AccessDeniedException("Not your booking");
        }
    }

    void ensureNoOverlap(UUID roomId, Instant start, Instant end, int bufferMinutes, UUID excludeId) {
        // Superset query padded by the maximum break, then the exact per-booking buffer test:
        // each booking's busy range is [start, end + its own buffer), same as the DB constraint.
        List<Booking> candidates = bookingRepository.findActiveOverlapping(roomId,
                start.minus(BookingRuleValidator.BOOKING_GAP),
                end.plus(BookingRuleValidator.BOOKING_GAP),
                excludeId);
        for (Booking b : candidates) {
            if (BookingRuleValidator.conflicts(b.getStartTime(), b.getEndTime(), b.getBufferMinutes(),
                    start, end, bufferMinutes)) {
                throw new BookingConflictException(new ConflictInfo(
                        b.getId(), b.getTitle(),
                        BusinessTime.toApi(b.getStartTime()), BusinessTime.toApi(b.getEndTime())));
            }
        }
    }
}
