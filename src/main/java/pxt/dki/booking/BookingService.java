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
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final BookingRuleValidator validator;
    private final CalendarSyncPort calendarSync;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, RoomRepository roomRepository,
                          UserRepository userRepository, BookingRuleValidator validator,
                          CalendarSyncPort calendarSync, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.validator = validator;
        this.calendarSync = calendarSync;
        this.clock = clock;
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
        bookingRepository.save(booking);

        calendarSync.enqueue(booking, OutboxAction.CREATE);
        return BookingResponse.from(booking);
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

        booking.setTitle(request.title());
        booking.setDescription(request.description());
        booking.setAttendeeCount(request.attendeeCount());
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setBufferMinutes(bufferMinutes);
        bookingRepository.save(booking);

        calendarSync.enqueue(booking, OutboxAction.UPDATE);
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
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> myBookings(AuthUser actor, Instant from, Instant to, BookingStatus status) {
        Instant effectiveFrom = from != null ? from : clock.instant();
        BookingStatus effectiveStatus = status != null ? status : BookingStatus.ACTIVE;
        Specification<Booking> spec = (root, q, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("user").get("id"), actor.id()));
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
