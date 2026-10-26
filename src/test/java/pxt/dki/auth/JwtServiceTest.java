package pxt.dki.auth;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;
import pxt.dki.user.Role;
import pxt.dki.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-32-bytes!";

    private JwtService service(Instant now) {
        return new JwtService(new JwtProperties(SECRET, Duration.ofHours(8)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("alice@dki.local");
        u.setRole(Role.USER);
        return u;
    }

    @Test
    void generateThenParse_returnsSubject() {
        Instant now = Instant.parse("2026-10-26T00:00:00Z");
        User u = user();

        String token = service(now).generate(u);

        assertThat(service(now).parseSubject(token)).isEqualTo(u.getId());
    }

    @Test
    void expiredToken_rejected() {
        Instant issueTime = Instant.parse("2026-10-26T00:00:00Z");
        String token = service(issueTime).generate(user());

        JwtService nineHoursLater = service(issueTime.plus(Duration.ofHours(9)));

        assertThatThrownBy(() -> nineHoursLater.parseSubject(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
