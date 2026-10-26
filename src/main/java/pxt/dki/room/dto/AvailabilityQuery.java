package pxt.dki.room.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public record AvailabilityQuery(
        @NotNull OffsetDateTime start,
        @NotNull OffsetDateTime end,
        @Min(1) Integer minCapacity,
        List<String> amenities) {
}
