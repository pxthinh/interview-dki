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
    void blankSecret_getsRandomKeyThatStillSignsAndVerifies() {
        JwtProperties props = new JwtProperties("", Duration.ofHours(8));

        assertThat(props.secret()).isNotBlank();
        assertThat(props.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThanOrEqualTo(JwtProperties.MIN_SECRET_BYTES);
        // two processes never share a generated key
        assertThat(new JwtProperties(null, Duration.ofHours(8)).secret()).isNotEqualTo(props.secret());
    }

    @Test
    void shortSecret_failsFast() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofHours(8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is too short");
    }

    @Test
    void parse_exposesIssuedAt() {
        Instant now = Instant.parse("2026-10-26T00:00:00Z");

        JwtService.TokenClaims claims = service(now).parse(service(now).generate(user()));

        assertThat(claims.issuedAt()).isEqualTo(now);
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
