package pxt.dki.room.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record RoomRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String location,
        @Min(1) int capacity,
        List<String> amenities,
        @Size(max = 255) String googleCalendarId,
        UUID adminUserId) {
}
