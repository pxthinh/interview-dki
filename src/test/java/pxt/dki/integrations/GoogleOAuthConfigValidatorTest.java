package pxt.dki.integrations;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** With personal OAuth enabled, a broken config must stop the app at boot, not corrupt user state later. */
class GoogleOAuthConfigValidatorTest {

    private static final String KEY_32 = Base64.getEncoder().encodeToString(new byte[32]);

    private static void validate(boolean enabled, String id, String secret, String redirect, String key) {
        new GoogleOAuthConfigValidator(new GoogleOAuthProperties(enabled, id, secret, redirect, key)).validate();
    }

    @Test
    void disabled_isNotValidated() {
        assertThatCode(() -> validate(false, "", "", "", "")).doesNotThrowAnyException();
    }

    @Test
    void validConfig_passes() {
        assertThatCode(() -> validate(true, "id", "secret", "http://cb", KEY_32)).doesNotThrowAnyException();
    }

    @Test
    void missingOrBadKey_failsFast() {
        assertThatThrownBy(() -> validate(true, "id", "secret", "http://cb", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GOOGLE_OAUTH_TOKEN_KEY");
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> validate(true, "id", "secret", "http://cb", sixteenBytes))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        // `openssl rand -hex 32` is valid base64 text but decodes to 48 bytes
        String hex = "a".repeat(64);
        assertThatThrownBy(() -> validate(true, "id", "secret", "http://cb", hex))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> validate(true, "id", "secret", "http://cb", "not base64 !!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingClientSettings_failFast() {
        assertThatThrownBy(() -> validate(true, "", "secret", "http://cb", KEY_32))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GOOGLE_OAUTH_CLIENT_ID");
        assertThatThrownBy(() -> validate(true, "id", " ", "http://cb", KEY_32))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GOOGLE_OAUTH_CLIENT_SECRET");
        assertThatThrownBy(() -> validate(true, "id", "secret", null, KEY_32))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GOOGLE_OAUTH_REDIRECT_URI");
    }
}
