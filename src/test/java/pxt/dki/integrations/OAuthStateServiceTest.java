package pxt.dki.integrations;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import pxt.dki.auth.JwtProperties;
import pxt.dki.auth.JwtService;
import pxt.dki.user.Role;
import pxt.dki.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OAuth state token must be its own credential type: never forgeable from a login JWT,
 * although both derive from app.jwt.secret. (The reverse direction - state used as a login
 * bearer - is asserted in GoogleIntegrationFlowTest against the real filter chain.)
 */
class OAuthStateServiceTest {

    private static final String SECRET = "change-me-change-me-change-me-change-me-32b";
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");

    private final JwtProperties props = new JwtProperties(SECRET, Duration.ofHours(8));
    private final OAuthStateService service =
            new OAuthStateService(props, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void state_roundtrips_user_id() {
        UUID userId = UUID.randomUUID();
        assertThat(service.parse(service.issue(userId))).isEqualTo(userId);
    }

    @Test
    void tampered_state_rejected() {
        String state = service.issue(UUID.randomUUID());
        String tampered = state.substring(0, state.length() - 3) + "abc";
        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void expired_state_rejected() {
        String state = service.issue(UUID.randomUUID());
        OAuthStateService later = new OAuthStateService(props,
                Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC)); // > 10-minute TTL
        assertThatThrownBy(() -> later.parse(state)).isInstanceOf(JwtException.class);
    }

    @Test
    void login_jwt_rejected_as_state() {
        JwtService jwtService = new JwtService(props, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("alice@dki.local");
        user.setRole(Role.USER);
        String loginToken = jwtService.generate(user);
        assertThatThrownBy(() -> service.parse(loginToken)).isInstanceOf(JwtException.class);
    }
}
