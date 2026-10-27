package pxt.dki.booking.dto;

import pxt.dki.common.error.ConflictInfo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Partial-success result: occurrences that were booked, and the ones skipped over a conflict. */
public record RecurringBookingResponse(
        UUID seriesId,
        List<BookingResponse> created,
        List<SkippedOccurrence> skipped) {

    public record SkippedOccurrence(OffsetDateTime startTime, OffsetDateTime endTime, ConflictInfo conflict) {
    }
}
