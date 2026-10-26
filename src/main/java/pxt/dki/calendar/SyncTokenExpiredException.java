package pxt.dki.calendar;

/** Google answered 410 GONE for a sync token: a full resync is required. */
public class SyncTokenExpiredException extends RuntimeException {

    public SyncTokenExpiredException(String message) {
        super(message);
    }
}
