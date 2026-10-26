package pxt.dki.common.error;

/** Maps to HTTP 404 with errorCode NOT_FOUND. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
