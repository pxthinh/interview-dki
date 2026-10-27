package pxt.dki.calendar;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@link BookingUserEvent}. */
public class BookingUserEventId implements Serializable {

    private UUID bookingId;
    private UUID userId;

    public BookingUserEventId() {
    }

    public BookingUserEventId(UUID bookingId, UUID userId) {
        this.bookingId = bookingId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BookingUserEventId other
                && Objects.equals(bookingId, other.bookingId)
                && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bookingId, userId);
    }
}
