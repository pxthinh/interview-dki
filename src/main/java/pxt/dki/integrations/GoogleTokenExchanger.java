package pxt.dki.integrations;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Real Google token endpoint calls; properties read at call time so boot works unconfigured. */
@Component
public class GoogleTokenExchanger implements TokenExchanger {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenExchanger.class);
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events";

    private final GoogleOAuthProperties properties;

    public GoogleTokenExchanger(GoogleOAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExchangeResult exchange(String code) {
        try {
            GoogleTokenResponse response = new GoogleAuthorizationCodeTokenRequest(
                    GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(),
                    properties.clientId(), properties.clientSecret(), code, properties.redirectUri())
                    .execute();
            String refreshToken = response.getRefreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new IllegalStateException("Google returned no refresh token");
            }
            // granular consent lets the user untick Calendar while granting openid/email
            String granted = response.getScope() == null ? "" : response.getScope();
            if (!java.util.Arrays.asList(granted.split(" ")).contains(CALENDAR_SCOPE)) {
                revoke(refreshToken);
                throw new InsufficientScopeException("Calendar permission was not granted");
            }
            String email = verifiedEmail(response.parseIdToken().getPayload(), properties.clientId());
            return new ExchangeResult(refreshToken, email);
        } catch (IllegalStateException | InsufficientScopeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Google code exchange failed: " + e.getMessage(), e);
        }
    }

    private static final java.util.Set<String> ISSUERS =
            java.util.Set.of("https://accounts.google.com", "accounts.google.com");

    /**
     * The email the account gets linked to: only from an ID token minted for this client by
     * Google, for a verified address. Anything else is a bad token, not a Google account.
     */
    static String verifiedEmail(com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload payload,
                                String clientId) {
        if (payload == null || !ISSUERS.contains(payload.getIssuer())) {
            throw new IllegalStateException("ID token issuer is not Google");
        }
        if (clientId == null || !clientId.equals(payload.getAudience())) {
            throw new IllegalStateException("ID token audience is not this client");
        }
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new IllegalStateException("Google account email is not verified");
        }
        return payload.getEmail();
    }

    @Override
    public void revoke(String refreshToken) {
        try {
            var transport = GoogleNetHttpTransport.newTrustedTransport();
            var content = new UrlEncodedContent(java.util.Map.of("token", refreshToken));
            transport.createRequestFactory()
                    .buildPostRequest(new GenericUrl(REVOKE_URL), content)
                    .setThrowExceptionOnExecuteError(false)
                    .execute()
                    .disconnect();
        } catch (Exception e) {
            log.warn("Google token revocation failed (ignored): {}", e.toString());
        }
    }
}
