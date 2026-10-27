package pxt.dki.integrations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Connect / callback / status / disconnect flow with a faked Google token exchange. */
@TestPropertySource(properties = {
        "app.google-oauth.enabled=true",
        "app.google-oauth.client-id=test-client",
        "app.google-oauth.client-secret=test-secret",
        "app.google-oauth.redirect-uri=http://localhost/api/v1/integrations/google/callback",
        // fixed base64 32-byte key for tests
        "app.google-oauth.token-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
})
@Import(GoogleIntegrationFlowTest.FakeExchangerConfig.class)
class GoogleIntegrationFlowTest extends AbstractIntegrationTest {

    static class FakeTokenExchanger implements TokenExchanger {
        final AtomicReference<String> lastRevoked = new AtomicReference<>();

        @Override
        public ExchangeResult exchange(String code) {
            if ("bad-code".equals(code)) {
                throw new IllegalStateException("exchange failed");
            }
            return new ExchangeResult("refresh-" + code, "alice.personal@gmail.com");
        }

        @Override
        public void revoke(String refreshToken) {
            lastRevoked.set(refreshToken);
        }
    }

    @TestConfiguration
    static class FakeExchangerConfig {
        @Bean
        @Primary
        FakeTokenExchanger fakeTokenExchanger() {
            return new FakeTokenExchanger();
        }
    }

    @Autowired
    FakeTokenExchanger exchanger;

    @Autowired
    OAuthStateService stateService;

    @Autowired
    TokenCipher tokenCipher;

    @Autowired
    JdbcTemplate jdbc;

    private String alice;
    private UUID aliceId;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        aliceId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.ALICE);
        jdbc.update("DELETE FROM user_google_accounts WHERE user_id = ?", aliceId);
    }

    @Test
    void status_initiallyDisconnected() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/google").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void connect_returnsAuthUrl_withOfflineConsentAndState() throws Exception {
        String body = mockMvc.perform(post("/api/v1/integrations/google/connect")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = objectMapper.readTree(body).get("authUrl").asText();
        assertThat(url).contains("accounts.google.com")
                .contains("access_type=offline")
                .contains("prompt=consent")
                .contains("client_id=test-client")
                .contains("state=");
    }

    @Test
    void callback_isPermitAll_storesEncryptedToken_andRedirects() throws Exception {
        String state = stateService.issue(aliceId);
        mockMvc.perform(get("/api/v1/integrations/google/callback")
                        .param("code", "ok-code").param("state", state)) // no Authorization header
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account?google=connected"));

        String enc = jdbc.queryForObject(
                "SELECT refresh_token_enc FROM user_google_accounts WHERE user_id = ?", String.class, aliceId);
        assertThat(enc).isNotEqualTo("refresh-ok-code");
        assertThat(tokenCipher.decrypt(enc)).isEqualTo("refresh-ok-code");

        mockMvc.perform(get("/api/v1/integrations/google").header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.googleEmail").value("alice.personal@gmail.com"))
                .andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void callback_withTamperedState_redirectsError_storesNothing() throws Exception {
        String state = stateService.issue(aliceId);
        String tampered = state.substring(0, state.length() - 3) + "abc";
        mockMvc.perform(get("/api/v1/integrations/google/callback")
                        .param("code", "ok-code").param("state", tampered))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account?google=error"));
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM user_google_accounts WHERE user_id = ?", Integer.class, aliceId);
        assertThat(rows).isZero();
    }

    @Test
    void stateToken_rejectedAsLoginBearer() throws Exception {
        String state = stateService.issue(aliceId);
        mockMvc.perform(get("/api/v1/bookings/me").header("Authorization", "Bearer " + state))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disconnect_deletesAccount_andRevokesToken() throws Exception {
        String state = stateService.issue(aliceId);
        mockMvc.perform(get("/api/v1/integrations/google/callback")
                        .param("code", "ok-code").param("state", state))
                .andExpect(status().isFound());

        mockMvc.perform(delete("/api/v1/integrations/google").header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM user_google_accounts WHERE user_id = ?", Integer.class, aliceId);
        assertThat(rows).isZero();
        assertThat(exchanger.lastRevoked.get()).isEqualTo("refresh-ok-code");
    }
}
