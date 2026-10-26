package pxt.dki.booking.dto;

import pxt.dki.booking.BookingStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Admin search filter; every field is optional. */
public record BookingSearchFilter(
        UUID roomId,
        UUID userId,
        OffsetDateTime from,
        OffsetDateTime to,
        BookingStatus status) {
}
