package pxt.dki.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * HS256 signing secret and token lifetime. The secret is resolved once at bind time so every
 * consumer (login JWTs, OAuth state) sees the same value: blank -> a random per-process key
 * (tokens die with the process), shorter than 32 bytes -> startup fails.
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration expiration) {

    private static final Logger log = LoggerFactory.getLogger(JwtProperties.class);
    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        secret = resolveSecret(secret);
    }

    private static String resolveSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] bytes = new byte[64];
            new SecureRandom().nextBytes(bytes);
            log.warn("JWT_SECRET is not set: using a random key generated at startup. Issued tokens "
                    + "will NOT survive a restart and every instance signs differently; set JWT_SECRET "
                    + "(at least {} bytes) for production.", MIN_SECRET_BYTES);
            return Base64.getEncoder().encodeToString(bytes);
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET is too short: " + length + " bytes, HS256 needs at least "
                    + MIN_SECRET_BYTES + " bytes (e.g. `openssl rand -base64 48`)");
        }
        return secret;
    }
}
