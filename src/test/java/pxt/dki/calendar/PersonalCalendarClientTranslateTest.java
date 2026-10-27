package pxt.dki.calendar;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Error classification of personal-calendar calls: what means "reconnect" vs "retry". */
class PersonalCalendarClientTranslateTest {

    private static GoogleJsonResponseException googleError(int code, String reason, String message) {
        GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
        info.setReason(reason);
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(code);
        details.setMessage(message);
        details.setErrors(List.of(info));
        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(code, "x", new HttpHeaders()), details);
    }

    @Test
    void insufficientScope403_meansReconnect() {
        RuntimeException e = PersonalCalendarClientImpl.translate(googleError(403, "insufficientPermissions",
                "Request had insufficient authentication scopes."));
        assertThat(e).isInstanceOf(GoogleAuthRevokedException.class);
    }

    @Test
    void rateLimit403_isRetryable_notRevoked() {
        RuntimeException e = PersonalCalendarClientImpl.translate(googleError(403, "rateLimitExceeded",
                "Rate Limit Exceeded"));
        assertThat(e).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidGrantDuringRefresh_meansReconnect() {
        // shape of google-auth-library's GoogleAuthException: an IOException whose message holds the body
        IOException refreshFailure = new IOException("Error getting access token for service account: 400 Bad Request",
                new IOException("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));
        assertThat(PersonalCalendarClientImpl.translate(refreshFailure))
                .isInstanceOf(GoogleAuthRevokedException.class);
    }

    @Test
    void notFound_isEventGone() {
        assertThat(PersonalCalendarClientImpl.translate(googleError(404, "notFound", "Not Found")))
                .isInstanceOf(CalendarEventNotFoundException.class);
    }
}
