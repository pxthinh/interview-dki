package pxt.dki.common.error;

/** A violated business rule. Maps to HTTP 422 with a machine-readable errorCode. */
public class BusinessRuleException extends RuntimeException {

    private final String errorCode;

    public BusinessRuleException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
