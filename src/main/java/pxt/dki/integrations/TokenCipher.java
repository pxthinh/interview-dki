package pxt.dki.integrations;

import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES-256-GCM at-rest encryption for Google refresh tokens. Key = base64 32 bytes from
 * GOOGLE_OAUTH_TOKEN_KEY (openssl rand -base64 32). Initialized lazily so the app boots with the
 * feature disabled and no key configured; storage format is base64(iv || ciphertext || tag) —
 * the encryptor prepends the random IV itself.
 */
@Component
public class TokenCipher {

    private final GoogleOAuthProperties properties;
    private volatile AesBytesEncryptor encryptor;

    public TokenCipher(GoogleOAuthProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] enc = encryptor().encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(enc);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] dec = encryptor().decrypt(Base64.getDecoder().decode(encrypted));
            return new String(dec, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed (wrong key or corrupt value)", e);
        }
    }

    private AesBytesEncryptor encryptor() {
        AesBytesEncryptor local = encryptor;
        if (local == null) {
            synchronized (this) {
                if (encryptor == null) {
                    if (properties.tokenKey() == null || properties.tokenKey().isBlank()) {
                        throw new IllegalStateException(
                                "GOOGLE_OAUTH_TOKEN_KEY is not set (generate with: openssl rand -base64 32)");
                    }
                    byte[] key = Base64.getDecoder().decode(properties.tokenKey());
                    encryptor = new AesBytesEncryptor(new SecretKeySpec(key, "AES"),
                            KeyGenerators.secureRandom(16), AesBytesEncryptor.CipherAlgorithm.GCM);
                }
                local = encryptor;
            }
        }
        return local;
    }
}
