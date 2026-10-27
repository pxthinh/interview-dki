package pxt.dki.integrations;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-256-GCM at-rest encryption for Google refresh tokens. No Spring context. */
class TokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    private TokenCipher cipher(String key) {
        return new TokenCipher(new GoogleOAuthProperties(true, "id", "secret", "http://cb", key));
    }

    @Test
    void encrypt_then_decrypt_roundtrips() {
        TokenCipher c = cipher(KEY);
        String enc = c.encrypt("1//refresh-token-value");
        assertThat(enc).isNotEqualTo("1//refresh-token-value");
        assertThat(c.decrypt(enc)).isEqualTo("1//refresh-token-value");
    }

    @Test
    void same_plaintext_encrypts_differently() {
        TokenCipher c = cipher(KEY);
        assertThat(c.encrypt("x")).isNotEqualTo(c.encrypt("x")); // random IV
    }

    @Test
    void decrypt_with_wrong_key_throws() {
        String enc = cipher(KEY).encrypt("secret");
        String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatThrownBy(() -> cipher(otherKey).decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blank_key_fails_lazily_on_first_use() {
        TokenCipher c = cipher(""); // constructing must not throw: app boots with the feature off
        assertThatThrownBy(() -> c.encrypt("x")).isInstanceOf(TokenKeyMisconfiguredException.class);
    }

    @Test
    void wrong_length_key_is_a_config_error_not_a_corrupt_token() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> cipher(sixteenBytes).decrypt("anything"))
                .isInstanceOf(TokenKeyMisconfiguredException.class);
    }

    @Test
    void corrupt_value_is_not_a_config_error() {
        assertThatThrownBy(() -> cipher(KEY).decrypt("bm90LWEtdmFsaWQtY2lwaGVydGV4dA=="))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TokenKeyMisconfiguredException.class);
    }
}
