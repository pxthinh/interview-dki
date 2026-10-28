package pxt.dki.integrations;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ID-token claims the exchange trusts before linking an account to an email. */
class GoogleTokenExchangerTest {

    private static final String CLIENT_ID = "test-client";

    private static GoogleIdToken.Payload payload() {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setIssuer("https://accounts.google.com");
        p.setAudience(CLIENT_ID);
        p.setEmail("alice.personal@gmail.com");
        p.setEmailVerified(true);
        return p;
    }

    @Test
    void verifiedGoogleToken_yieldsEmail() {
        assertThat(GoogleTokenExchanger.verifiedEmail(payload(), CLIENT_ID)).isEqualTo("alice.personal@gmail.com");

        GoogleIdToken.Payload bareIssuer = payload();
        bareIssuer.setIssuer("accounts.google.com");
        assertThat(GoogleTokenExchanger.verifiedEmail(bareIssuer, CLIENT_ID)).isEqualTo("alice.personal@gmail.com");
    }

    @Test
    void unverifiedEmail_rejected() {
        GoogleIdToken.Payload p = payload();
        p.setEmailVerified(false);
        assertThatThrownBy(() -> GoogleTokenExchanger.verifiedEmail(p, CLIENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not verified");

        GoogleIdToken.Payload missing = payload();
        missing.setEmailVerified(null);
        assertThatThrownBy(() -> GoogleTokenExchanger.verifiedEmail(missing, CLIENT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongAudience_rejected() {
        GoogleIdToken.Payload p = payload();
        p.setAudience("someone-elses-client");
        assertThatThrownBy(() -> GoogleTokenExchanger.verifiedEmail(p, CLIENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void foreignIssuer_rejected() {
        GoogleIdToken.Payload p = payload();
        p.setIssuer("https://evil.example");
        assertThatThrownBy(() -> GoogleTokenExchanger.verifiedEmail(p, CLIENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
    }
}
