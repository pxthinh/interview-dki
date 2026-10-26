package pxt.dki.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull UUID roomId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @Positive Integer attendeeCount,
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime) {
}
