package pxt.dki.integrations;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import pxt.dki.auth.JwtProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Short-lived OAuth `state` token carrying the connecting user's id and a browser-binding nonce
 * across the Google redirect. The same nonce goes into an HttpOnly cookie on the browser that
 * clicked "connect"; the callback requires both to match, so a state handed to (or leaked from)
 * someone else's browser links nothing.
 *
 * Signed with a key DERIVED from app.jwt.secret (never the login key itself): state rides URLs
 * and logs, so it must never double as a login bearer, and a login JWT must never pass as state.
 */
@Service
public class OAuthStateService {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final String PURPOSE = "google-oauth-state";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record State(UUID userId, String nonce) {
    }

    private final SecretKey key;
    private final Clock clock;

    public OAuthStateService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(
                (properties.secret() + ":" + PURPOSE).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** 256 random bits, URL/cookie safe. */
    public String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String issue(UUID userId, String nonce) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("purpose", PURPOSE)
                .claim("nonce", nonce)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException when invalid, expired, or not a state token */
    public State parse(String state) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(state)
                .getPayload();
        if (!PURPOSE.equals(claims.get("purpose", String.class))) {
            throw new JwtException("Not an OAuth state token");
        }
        return new State(UUID.fromString(claims.getSubject()), claims.get("nonce", String.class));
    }
}
