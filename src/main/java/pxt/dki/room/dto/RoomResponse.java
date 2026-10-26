package pxt.dki.room.dto;

import pxt.dki.common.time.BusinessTime;
import pxt.dki.room.Room;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RoomResponse(
        UUID id,
        String name,
        String location,
        int capacity,
        List<String> amenities,
        boolean active,
        String googleCalendarId,
        UUID adminUserId,
        OffsetDateTime createdAt) {

    public static RoomResponse from(Room room) {
        return new RoomResponse(room.getId(), room.getName(), room.getLocation(), room.getCapacity(),
                room.getAmenities(), room.isActive(), room.getGoogleCalendarId(),
                room.getAdminUserId(), BusinessTime.toApi(room.getCreatedAt()));
    }
}
