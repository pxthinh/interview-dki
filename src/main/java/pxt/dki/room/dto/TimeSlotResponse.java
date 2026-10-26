package pxt.dki.room.dto;

import java.time.OffsetDateTime;

/** One bookable start time on the 15-minute grid; only slots with availableRooms > 0 are returned. */
public record TimeSlotResponse(OffsetDateTime startTime, OffsetDateTime endTime, int availableRooms) {
}
