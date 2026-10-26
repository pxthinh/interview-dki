package pxt.dki.room;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.booking.BookingRepository;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.error.ResourceNotFoundException;
import pxt.dki.room.dto.AvailabilityQuery;
import pxt.dki.room.dto.RoomRequest;
import pxt.dki.room.dto.RoomResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RoomService {

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;

    public RoomService(RoomRepository roomRepository, BookingRepository bookingRepository) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
    }

    public RoomResponse create(RoomRequest request) {
        if (roomRepository.existsByNameIgnoreCase(request.name())) {
            throw new BusinessRuleException("ROOM_NAME_TAKEN", "A room with this name already exists");
        }
        Room room = new Room();
        apply(room, request);
        return RoomResponse.from(roomRepository.save(room));
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
                        .findActiveOverlapping(room.getId(), start, end, null).isEmpty())
                .map(RoomResponse::from)
                .toList();
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
