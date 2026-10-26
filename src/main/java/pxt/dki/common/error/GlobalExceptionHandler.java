package pxt.dki.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(BookingConflictException.class)
    ProblemDetail conflict(BookingConflictException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "BOOKING_CONFLICT", ex.getMessage());
        if (ex.getConflict() != null) {
            pd.setProperty("conflict", ex.getConflict());
        }
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_RANGE", ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Invalid value for parameter '" + ex.getName() + "'");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ProblemDetail missingParam(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Missing required parameter '" + ex.getParameterName() + "'");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail authentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid credentials");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        if (message != null && message.contains("bookings_no_overlap")) {
            // Lost the race: another transaction inserted an overlapping booking first.
            return conflict(new BookingConflictException(null));
        }
        log.error("Data integrity violation", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Malformed request body (check JSON syntax, date-time offsets, enums and UUIDs)");
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The booking was changed by someone else; reload and try again");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        // Framework exceptions (405, 406, 415, unknown path, bad sort property, ...) already
        // carry their own status + ProblemDetail body; pass those through instead of a 500.
        if (ex instanceof org.springframework.web.ErrorResponse er) {
            ProblemDetail pd = er.getBody();
            pd.setProperty("errorCode", "REQUEST_ERROR");
            return pd;
        }
        if (ex instanceof org.springframework.data.mapping.PropertyReferenceException) {
            return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
