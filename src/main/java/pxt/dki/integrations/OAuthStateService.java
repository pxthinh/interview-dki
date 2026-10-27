package pxt.dki.integrations;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import pxt.dki.auth.JwtProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Short-lived OAuth `state` token carrying the connecting user's id across the Google redirect.
 * Signed with a key DERIVED from app.jwt.secret (never the login key itself): state rides URLs
 * and logs, so it must never double as a login bearer, and a login JWT must never pass as state.
 */
@Service
public class OAuthStateService {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final String PURPOSE = "google-oauth-state";

    private final SecretKey key;
    private final Clock clock;

    public OAuthStateService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(
                (properties.secret() + ":" + PURPOSE).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String issue(UUID userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("purpose", PURPOSE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException when invalid, expired, or not a state token */
    public UUID parse(String state) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(state)
                .getPayload();
        if (!PURPOSE.equals(claims.get("purpose", String.class))) {
            throw new JwtException("Not an OAuth state token");
        }
        return UUID.fromString(claims.getSubject());
    }
}
