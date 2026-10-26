package pxt.dki.booking;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.auth.AuthUser;
import pxt.dki.booking.dto.BookingResponse;
import pxt.dki.booking.dto.CreateBookingRequest;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final BookingRuleValidator validator;
    private final CalendarSyncPort calendarSync;

    public BookingService(BookingRepository bookingRepository, RoomRepository roomRepository,
                          UserRepository userRepository, BookingRuleValidator validator,
                          CalendarSyncPort calendarSync) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.validator = validator;
        this.calendarSync = calendarSync;
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
        ensureNoOverlap(room.getId(), start, end, null);

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

    Booking require(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    void requireOwnerOrAdmin(AuthUser actor, Booking booking) {
        if (!actor.isAdmin() && !booking.getUser().getId().equals(actor.id())) {
            throw new AccessDeniedException("Not your booking");
        }
    }

    void ensureNoOverlap(UUID roomId, Instant start, Instant end, UUID excludeId) {
        List<Booking> overlapping = bookingRepository.findActiveOverlapping(roomId, start, end, excludeId);
        if (!overlapping.isEmpty()) {
            Booking first = overlapping.get(0);
            throw new BookingConflictException(new ConflictInfo(
                    first.getId(), first.getTitle(),
                    BusinessTime.toApi(first.getStartTime()), BusinessTime.toApi(first.getEndTime())));
        }
    }
}
