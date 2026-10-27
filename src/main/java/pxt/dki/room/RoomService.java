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
import pxt.dki.auth.AuthUser;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.room.dto.TimeSlotsResponse;
import pxt.dki.user.UserRepository;
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
    private final UserRepository userRepository;
    private final GoogleCalendarClient calendarClient;
    private final CalendarProperties calendarProperties;
    private final Clock clock;
    private final pxt.dki.settings.SettingsService settings;

    public RoomService(RoomRepository roomRepository, BookingRepository bookingRepository,
                       UserRepository userRepository, GoogleCalendarClient calendarClient,
                       CalendarProperties calendarProperties, Clock clock,
                       pxt.dki.settings.SettingsService settings) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.calendarClient = calendarClient;
        this.calendarProperties = calendarProperties;
        this.clock = clock;
        this.settings = settings;
    }

    public RoomResponse create(AuthUser actor, RoomRequest request) {
        if (roomRepository.existsByNameIgnoreCase(request.name())) {
            throw new BusinessRuleException("ROOM_NAME_TAKEN", "A room with this name already exists");
        }
        Room room = new Room();
        apply(room, request);
        // every room has an owning admin; default: whoever creates it
        room.setAdminUserId(resolveAdmin(request.adminUserId(), actor.id()));
        provisionCalendarIfMissing(room);
        return RoomResponse.from(roomRepository.save(room));
    }

    private UUID resolveAdmin(UUID requested, UUID fallback) {
        if (requested == null) {
            return fallback;
        }
        if (!userRepository.existsById(requested)) {
            throw new BusinessRuleException("ADMIN_USER_NOT_FOUND", "adminUserId does not match any user");
        }
        return requested;
    }

    /**
     * Every room gets its own Google Calendar: when sync is enabled and the admin did not
     * supply an existing calendar id, one is created automatically (named after the room,
     * owned by the service account). A Google failure never blocks room creation; the admin
     * can attach a calendar later via PUT /rooms/{id}.
     */
    private void provisionCalendarIfMissing(Room room) {
        boolean missing = room.getGoogleCalendarId() == null || room.getGoogleCalendarId().isBlank();
        // not gated on the runtime pause: the room would otherwise never get its calendar
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
        if (request.adminUserId() != null) {
            room.setAdminUserId(resolveAdmin(request.adminUserId(), room.getAdminUserId()));
        }
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
                        .stream()
                        .noneMatch(b -> BookingRuleValidator.conflicts(
                                b.getStartTime(), b.getEndTime(), b.getBufferMinutes(),
                                start, end, bufferMinutes(room))))
                .map(RoomResponse::from)
                .toList();
    }

    /**
     * Pickable times for the booking form, all on the 15-minute grid within 07:00-20:00 VN.
     * Without {@code start}: valid start times (able to host at least a 15-minute meeting).
     * With {@code start}: additionally the valid end times for it, capped at 8 hours and at
     * closing time. A time is offered only when at least one matching room is free for the
     * window including the mandatory 15-minute break; fully-booked times are omitted so the
     * UI never shows a choice that cannot be booked.
     */
    @Transactional(readOnly = true)
    public TimeSlotsResponse findTimeSlots(LocalDate date, java.time.OffsetDateTime start,
                                           Integer minCapacity, List<String> amenities) {
        return findTimeSlots(date, start, minCapacity, amenities, null, null);
    }

    /**
     * Variant for editing: {@code roomId} narrows the picker to one room and
     * {@code excludeBookingId} ignores the booking being edited, so its own current slot
     * still shows as selectable.
     */
    @Transactional(readOnly = true)
    public TimeSlotsResponse findTimeSlots(LocalDate date, java.time.OffsetDateTime start,
                                           Integer minCapacity, List<String> amenities,
                                           UUID roomId, UUID excludeBookingId) {
        LocalDate today = BusinessTime.today(clock);
        LocalDate effectiveDate = date != null ? date : today;
        int maxDaysAhead = settings.maxDaysAhead();
        if (effectiveDate.isBefore(today) || effectiveDate.isAfter(today.plusDays(maxDaysAhead))) {
            throw new IllegalArgumentException(
                    "date must be between today and " + maxDaysAhead + " days ahead");
        }

        List<Room> rooms = roomRepository.findByActiveTrueOrderByCapacityAscNameAsc().stream()
                .filter(r -> roomId == null || r.getId().equals(roomId))
                .filter(r -> minCapacity == null || r.getCapacity() >= minCapacity)
                .filter(r -> amenities == null || amenities.isEmpty() || r.getAmenities().containsAll(amenities))
                .toList();
        if (rooms.isEmpty()) {
            return new TimeSlotsResponse(List.of(), List.of());
        }

        Duration gap = BookingRuleValidator.BOOKING_GAP;
        Duration step = Duration.ofMinutes(15);
        Instant dayStart = effectiveDate.atTime(LocalTime.of(7, 0)).atZone(BusinessTime.ZONE).toInstant();
        Instant dayEnd = effectiveDate.atTime(LocalTime.of(20, 0)).atZone(BusinessTime.ZONE).toInstant();
        List<Booking> dayBookings = bookingRepository
                .findByRoomIdInAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                        rooms.stream().map(Room::getId).toList(), BookingStatus.ACTIVE,
                        dayEnd.plus(gap), dayStart.minus(gap))
                .stream()
                .filter(b -> excludeBookingId == null || !b.getId().equals(excludeBookingId))
                .toList();
        Instant now = clock.instant();

        List<TimeSlotsResponse.TimeOption> startTimes = new ArrayList<>();
        for (Instant s = dayStart; !s.plus(step).isAfter(dayEnd); s = s.plus(step)) {
            if (!s.isAfter(now)) {
                continue; // start must be in the future
            }
            int free = countFree(rooms, dayBookings, s, s.plus(step), gap);
            if (free > 0) {
                startTimes.add(new TimeSlotsResponse.TimeOption(BusinessTime.toApi(s), free));
            }
        }

        List<TimeSlotsResponse.TimeOption> endTimes = new ArrayList<>();
        if (start != null) {
            Instant s = start.toInstant();
            var startVn = BusinessTime.toBusiness(s);
            if (startVn.getMinute() % 15 != 0 || startVn.getSecond() != 0 || startVn.getNano() != 0) {
                throw new IllegalArgumentException("start must align to the 15-minute grid");
            }
            if (!startVn.toLocalDate().equals(effectiveDate) || s.isBefore(dayStart) || !s.plus(step).isAfter(s)
                    || s.plus(step).isAfter(dayEnd)) {
                throw new IllegalArgumentException("start must lie within 07:00-19:45 of the requested date");
            }
            Instant latestEnd = min(s.plus(Duration.ofHours(8)), dayEnd);
            for (Instant e = s.plus(step); !e.isAfter(latestEnd); e = e.plus(step)) {
                int free = countFree(rooms, dayBookings, s, e, gap);
                if (free == 0) {
                    break; // longer meetings only get harder; no later end can be free
                }
                endTimes.add(new TimeSlotsResponse.TimeOption(BusinessTime.toApi(e), free));
            }
        }
        return new TimeSlotsResponse(startTimes, endTimes);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static int countFree(List<Room> rooms, List<Booking> bookings,
                                 Instant start, Instant end, Duration unusedMaxGap) {
        int free = 0;
        for (Room room : rooms) {
            boolean busy = bookings.stream().anyMatch(b ->
                    b.getRoom().getId().equals(room.getId())
                            && BookingRuleValidator.conflicts(
                                    b.getStartTime(), b.getEndTime(), b.getBufferMinutes(),
                                    start, end, bufferMinutes(room)));
            if (!busy) {
                free++;
            }
        }
        return free;
    }

    private static int bufferMinutes(Room room) {
        return room.isBufferEnabled() ? (int) BookingRuleValidator.BOOKING_GAP.toMinutes() : 0;
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
        if (request.bufferEnabled() != null) {
            room.setBufferEnabled(request.bufferEnabled());
        }
    }
}
