package pxt.dki.integrations;

/**
 * GOOGLE_OAUTH_TOKEN_KEY is missing or not a base64 32-byte key: a deployment problem that
 * affects every stored token, unlike one user's unreadable value.
 */
public class TokenKeyMisconfiguredException extends IllegalStateException {

    public TokenKeyMisconfiguredException(String message) {
        super(message);
    }
}
