package pxt.dki.integrations;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * With personal OAuth enabled, refuse to boot on a broken configuration instead of failing
 * later - after users already consented, or while reading stored tokens.
 */
@Component
public class GoogleOAuthConfigValidator implements InitializingBean {

    private final GoogleOAuthProperties properties;

    public GoogleOAuthConfigValidator(GoogleOAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        if (!properties.enabled()) {
            return;
        }
        require(properties.clientId(), "GOOGLE_OAUTH_CLIENT_ID");
        require(properties.clientSecret(), "GOOGLE_OAUTH_CLIENT_SECRET");
        require(properties.redirectUri(), "GOOGLE_OAUTH_REDIRECT_URI");
        TokenCipher.decodeKey(properties.tokenKey());
    }

    private static void require(String value, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(env + " must be set when GOOGLE_OAUTH_ENABLED is on");
        }
    }
}
