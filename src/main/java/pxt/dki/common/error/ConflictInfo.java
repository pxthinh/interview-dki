package pxt.dki.common.error;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Details of the booking that already occupies a requested slot. */
public record ConflictInfo(UUID bookingId, String title, OffsetDateTime startTime, OffsetDateTime endTime) {
}
