package pxt.dki.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.booking.Booking;
import pxt.dki.booking.BookingRepository;
import pxt.dki.booking.BookingRuleValidator;
import pxt.dki.booking.BookingStatus;
import pxt.dki.calendar.CalendarProperties;
import pxt.dki.calendar.GoogleCalendarClient;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.room.dto.TimeSlotResponse;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.error.ResourceNotFoundException;
import pxt.dki.room.dto.AvailabilityQuery;
import pxt.dki.room.dto.RoomRequest;
import pxt.dki.room.dto.RoomResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;
    private final GoogleCalendarClient calendarClient;
    private final CalendarProperties calendarProperties;
    private final Clock clock;

    public RoomService(RoomRepository roomRepository, BookingRepository bookingRepository,
                       GoogleCalendarClient calendarClient, CalendarProperties calendarProperties,
                       Clock clock) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
        this.calendarClient = calendarClient;
        this.calendarProperties = calendarProperties;
        this.clock = clock;
    }

    public RoomResponse create(RoomRequest request) {
        if (roomRepository.existsByNameIgnoreCase(request.name())) {
            throw new BusinessRuleException("ROOM_NAME_TAKEN", "A room with this name already exists");
        }
        Room room = new Room();
        apply(room, request);
        provisionCalendarIfMissing(room);
        return RoomResponse.from(roomRepository.save(room));
    }

    /**
     * Every room gets its own Google Calendar: when sync is enabled and the admin did not
     * supply an existing calendar id, one is created automatically (named after the room,
     * owned by the service account). A Google failure never blocks room creation; the admin
     * can attach a calendar later via PUT /rooms/{id}.
     */
    private void provisionCalendarIfMissing(Room room) {
        boolean missing = room.getGoogleCalendarId() == null || room.getGoogleCalendarId().isBlank();
        if (!calendarProperties.enabled() || !missing) {
            return;
        }
        try {
            room.setGoogleCalendarId(calendarClient.createCalendar(room.getName()));
        } catch (Exception e) {
            log.warn("Could not auto-create Google Calendar for room '{}': {}", room.getName(), e.toString());
        }
    }

    public RoomResponse update(UUID id, RoomRequest request) {
        Room room = require(id);
        if (!room.getName().equalsIgnoreCase(request.name())
                && roomRepository.existsByNameIgnoreCase(request.name())) {
            throw new BusinessRuleException("ROOM_NAME_TAKEN", "A room with this name already exists");
        }
        apply(room, request);
        return RoomResponse.from(roomRepository.save(room));
    }

    public RoomResponse setActive(UUID id, boolean active) {
        Room room = require(id);
        room.setActive(active);
        return RoomResponse.from(roomRepository.save(room));
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID id) {
        return RoomResponse.from(require(id));
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> list(Boolean active) {
        List<Room> rooms = active != null && active
                ? roomRepository.findByActiveTrueOrderByCapacityAscNameAsc()
                : roomRepository.findAllByOrderByNameAsc();
        return rooms.stream().map(RoomResponse::from).toList();
    }

    /**
     * Active rooms that satisfy capacity and amenity filters and have no ACTIVE booking
     * overlapping [start, end). Sorted by capacity ascending: the closest fit first.
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> findAvailable(AvailabilityQuery query) {
        Instant start = query.start().toInstant();
        Instant end = query.end().toInstant();
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        return roomRepository.findByActiveTrueOrderByCapacityAscNameAsc().stream()
                .filter(room -> query.minCapacity() == null || room.getCapacity() >= query.minCapacity())
                .filter(room -> query.amenities() == null || query.amenities().isEmpty()
                        || room.getAmenities().containsAll(query.amenities()))
                .filter(room -> bookingRepository
                        .findActiveOverlapping(room.getId(),
                                start.minus(BookingRuleValidator.BOOKING_GAP),
                                end.plus(BookingRuleValidator.BOOKING_GAP), null)
                        .isEmpty())
                .map(RoomResponse::from)
                .toList();
    }

    /**
     * Bookable start times on the 15-minute grid for one day and meeting length.
     * A slot is returned only when at least one matching room is free for the whole window
     * plus the mandatory 15-minute break on both sides; fully-booked slots are omitted so
     * the UI never offers a time that cannot be booked.
     */
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> findTimeSlots(LocalDate date, int durationMinutes,
                                                Integer minCapacity, List<String> amenities) {
        if (durationMinutes < 15 || durationMinutes > 480 || durationMinutes % 15 != 0) {
            throw new IllegalArgumentException("durationMinutes must be a multiple of 15 between 15 and 480");
        }
        LocalDate today = BusinessTime.today(clock);
        LocalDate effectiveDate = date != null ? date : today;
        if (effectiveDate.isBefore(today) || effectiveDate.isAfter(today.plusDays(90))) {
            throw new IllegalArgumentException("date must be between today and 90 days ahead");
        }

        List<Room> rooms = roomRepository.findByActiveTrueOrderByCapacityAscNameAsc().stream()
                .filter(r -> minCapacity == null || r.getCapacity() >= minCapacity)
                .filter(r -> amenities == null || amenities.isEmpty() || r.getAmenities().containsAll(amenities))
                .toList();
        if (rooms.isEmpty()) {
            return List.of();
        }

        Duration gap = BookingRuleValidator.BOOKING_GAP;
        Instant dayStart = effectiveDate.atTime(LocalTime.of(7, 0)).atZone(BusinessTime.ZONE).toInstant();
        Instant dayEnd = effectiveDate.atTime(LocalTime.of(20, 0)).atZone(BusinessTime.ZONE).toInstant();
        List<Booking> dayBookings = bookingRepository
                .findByRoomIdInAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                        rooms.stream().map(Room::getId).toList(), BookingStatus.ACTIVE,
                        dayEnd.plus(gap), dayStart.minus(gap));

        Instant now = clock.instant();
        Duration duration = Duration.ofMinutes(durationMinutes);
        List<TimeSlotResponse> slots = new ArrayList<>();
        for (Instant s = dayStart; !s.plus(duration).isAfter(dayEnd); s = s.plus(Duration.ofMinutes(15))) {
            if (!s.isAfter(now)) {
                continue; // start must be in the future
            }
            Instant slotStart = s;
            Instant slotEnd = s.plus(duration);
            int free = 0;
            for (Room room : rooms) {
                boolean busy = dayBookings.stream().anyMatch(b ->
                        b.getRoom().getId().equals(room.getId())
                                && b.getStartTime().isBefore(slotEnd.plus(gap))
                                && b.getEndTime().isAfter(slotStart.minus(gap)));
                if (!busy) {
                    free++;
                }
            }
            if (free > 0) {
                slots.add(new TimeSlotResponse(
                        BusinessTime.toApi(slotStart), BusinessTime.toApi(slotEnd), free));
            }
        }
        return slots;
    }

    public Room require(UUID id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    private void apply(Room room, RoomRequest request) {
        room.setName(request.name());
        room.setLocation(request.location());
        room.setCapacity(request.capacity());
        room.setAmenities(request.amenities() == null ? new ArrayList<>() : new ArrayList<>(request.amenities()));
        room.setGoogleCalendarId(request.googleCalendarId());
    }
}
