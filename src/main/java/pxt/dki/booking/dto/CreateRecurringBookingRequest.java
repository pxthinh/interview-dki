package pxt.dki.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Weekly series: startTime/endTime describe the first occurrence, repeated {@code weeks} times. */
public record CreateRecurringBookingRequest(
        @NotNull UUID roomId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @Positive Integer attendeeCount,
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime,
        @Size(max = 50) List<UUID> participantIds,
        @NotNull @Min(2) @Max(26) Integer weeks) {
}
