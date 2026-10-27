package pxt.dki.integrations;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-user Google OAuth (personal calendar copies). Independent of the Service Account sync
 * (app.calendar.*); the admin runtime toggle calendar.sync-enabled gates both.
 */
@ConfigurationProperties("app.google-oauth")
public record GoogleOAuthProperties(
        boolean enabled,
        String clientId,
        String clientSecret,
        String redirectUri,
        String tokenKey) {
}
