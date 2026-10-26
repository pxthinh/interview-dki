package pxt.dki.common.error;

/**
 * The requested slot overlaps an ACTIVE booking. Maps to HTTP 409.
 * {@code conflict} may be null when the clash was detected by the database constraint
 * rather than the service-level check.
 */
public class BookingConflictException extends RuntimeException {

    private final ConflictInfo conflict;

    public BookingConflictException(ConflictInfo conflict) {
        super("The requested time slot overlaps an existing booking");
        this.conflict = conflict;
    }

    public ConflictInfo getConflict() {
        return conflict;
    }
}
