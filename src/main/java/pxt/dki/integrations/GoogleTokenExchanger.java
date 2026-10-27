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
            String email = response.parseIdToken().getPayload().getEmail();
            return new ExchangeResult(refreshToken, email);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Google code exchange failed: " + e.getMessage(), e);
        }
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
