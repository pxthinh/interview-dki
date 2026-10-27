package pxt.dki.integrations;

/** The user connected Google but unticked the Calendar permission on the consent screen. */
public class InsufficientScopeException extends RuntimeException {

    public InsufficientScopeException(String message) {
        super(message);
    }
}
