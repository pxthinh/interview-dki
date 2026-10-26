package pxt.dki.room.dto;

import jakarta.validation.constraints.NotNull;

public record RoomStatusRequest(@NotNull Boolean active) {
}
