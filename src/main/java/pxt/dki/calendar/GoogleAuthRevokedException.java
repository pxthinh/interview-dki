package pxt.dki.calendar;

/** The user's Google grant was revoked (invalid_grant): reconnect required, retrying is useless. */
public class GoogleAuthRevokedException extends RuntimeException {

    public GoogleAuthRevokedException(String message) {
        super(message);
    }
}
