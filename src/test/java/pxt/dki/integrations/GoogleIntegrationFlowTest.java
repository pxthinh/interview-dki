package pxt.dki.integrations;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
            if ("no-scope".equals(code)) {
                throw new InsufficientScopeException("calendar.events not granted");
            }
            if ("null-email".equals(code)) {
                return new ExchangeResult("refresh-" + code, null); // violates NOT NULL on save
            }
            String email = "other-account".equals(code) ? "alice.other@gmail.com" : "alice.personal@gmail.com";
            return new ExchangeResult("refresh-" + code, email);
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

    /** What the browser holds after clicking "Kết nối": the state in Google's URL + the nonce cookie. */
    private record Started(String state, Cookie nonce) {
    }

    @Autowired
    FakeTokenExchanger exchanger;

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

    private Started start(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/integrations/google/connect")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(result.getResponse().getContentAsString()).get("authUrl").asText();
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("connect must set the nonce cookie").isNotNull()
                .contains("HttpOnly").contains("SameSite=Lax");
        String pair = setCookie.split(";", 2)[0];
        String[] nv = pair.split("=", 2);
        return new Started(state, new Cookie(nv[0], nv[1]));
    }

    private org.springframework.test.web.servlet.ResultActions callback(String code, Started s) throws Exception {
        var request = get("/api/v1/integrations/google/callback").param("code", code).param("state", s.state());
        if (s.nonce() != null) {
            request.cookie(s.nonce());
        }
        return mockMvc.perform(request); // no Authorization header: Google's redirect carries none
    }

    private int accountRows() {
        return jdbc.queryForObject("SELECT count(*) FROM user_google_accounts WHERE user_id = ?", Integer.class, aliceId);
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
        callback("ok-code", start(alice))
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
    void callback_withoutTheConnectingBrowsersCookie_isRejected() throws Exception {
        // the attacker's (or a leaked) state replayed from a browser that never clicked connect
        Started s = start(alice);
        callback("ok-code", new Started(s.state(), null))
                .andExpect(header().string("Location", "/account?google=error"));
        assertThat(accountRows()).isZero();
    }

    @Test
    void callback_withAnotherConnectsCookie_isRejected() throws Exception {
        Started first = start(alice);
        Started second = start(alice);
        callback("ok-code", new Started(first.state(), second.nonce()))
                .andExpect(header().string("Location", "/account?google=error"));
        assertThat(accountRows()).isZero();
    }

    @Test
    void callback_withTamperedState_redirectsError_storesNothing() throws Exception {
        Started s = start(alice);
        String tampered = s.state().substring(0, s.state().length() - 3) + "abc";
        callback("ok-code", new Started(tampered, s.nonce()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account?google=error"));
        assertThat(accountRows()).isZero();
    }

    @Test
    void callback_whenSavingFails_redirectsError_notA500() throws Exception {
        callback("null-email", start(alice))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account?google=error"));
        assertThat(accountRows()).isZero();
    }

    @Test
    void stateToken_rejectedAsLoginBearer() throws Exception {
        String state = start(alice).state();
        mockMvc.perform(get("/api/v1/bookings/me").header("Authorization", "Bearer " + state))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_withoutCalendarScope_redirectsScope_storesNothing() throws Exception {
        callback("no-scope", start(alice))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/account?google=scope"));
        assertThat(accountRows()).isZero();
    }

    private String bookAsAlice(UUID room, int sh, int eh) throws Exception {
        LocalDate day = TestData.nextWeekday();
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Before connect","startTime":"%s","endTime":"%s"}"""
                                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private UUID newRoom() {
        UUID room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F10', 10, true, now(), now())""", room, "Resync-" + UUID.randomUUID());
        return room;
    }

    @Test
    void connecting_resyncsBookingsMadeBeforehand() throws Exception {
        String bookingId = bookAsAlice(newRoom(), 9, 10); // alice not connected yet -> no personal rows
        Integer before = jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox WHERE booking_id = ?::uuid AND target_user_id = ?""",
                Integer.class, bookingId, aliceId);
        assertThat(before).isZero();

        callback("ok-code", start(alice)).andExpect(header().string("Location", "/account?google=connected"));

        Integer after = jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox
                WHERE booking_id = ?::uuid AND target_user_id = ? AND action = 'CREATE' AND status = 'PENDING'""",
                Integer.class, bookingId, aliceId);
        assertThat(after).isEqualTo(1);
    }

    @Test
    void reconnectingAnotherGoogleAccount_dropsOldEventMappings() throws Exception {
        String bookingId = bookAsAlice(newRoom(), 11, 12);
        callback("ok-code", start(alice)).andExpect(header().string("Location", "/account?google=connected"));
        // copy pushed to the first account
        jdbc.update("""
                INSERT INTO booking_user_events (booking_id, user_id, google_event_id, google_email, created_at)
                VALUES (?::uuid, ?, 'old-account-event', 'alice.personal@gmail.com', now())""", bookingId, aliceId);

        callback("other-account", start(alice)).andExpect(header().string("Location", "/account?google=connected"));

        Integer mappings = jdbc.queryForObject(
                "SELECT count(*) FROM booking_user_events WHERE user_id = ?", Integer.class, aliceId);
        assertThat(mappings).isZero(); // old ids belong to the other Google account
        assertThat(jdbc.queryForObject("SELECT google_email FROM user_google_accounts WHERE user_id = ?",
                String.class, aliceId)).isEqualTo("alice.other@gmail.com");
    }

    @Test
    void reconnectingSameGoogleAccount_afterDisconnect_keepsEventMappings() throws Exception {
        String bookingId = bookAsAlice(newRoom(), 13, 14);
        jdbc.update("DELETE FROM booking_user_events WHERE user_id = ?", aliceId);
        jdbc.update("""
                INSERT INTO booking_user_events (booking_id, user_id, google_event_id, google_email, created_at)
                VALUES (?::uuid, ?, 'same-account-event', 'alice.personal@gmail.com', now())""", bookingId, aliceId);

        callback("ok-code", start(alice)).andExpect(header().string("Location", "/account?google=connected"));

        assertThat(jdbc.queryForObject("""
                SELECT google_event_id FROM booking_user_events WHERE booking_id = ?::uuid AND user_id = ?""",
                String.class, bookingId, aliceId)).isEqualTo("same-account-event");
    }

    @Test
    void disconnect_deletesAccount_andRevokesToken() throws Exception {
        callback("ok-code", start(alice)).andExpect(status().isFound());

        mockMvc.perform(delete("/api/v1/integrations/google").header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        assertThat(accountRows()).isZero();
        assertThat(exchanger.lastRevoked.get()).isEqualTo("refresh-ok-code");
    }

    @Test
    void deactivatingAUser_unlinksTheirGoogleAccount_andRevokesTheToken() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        String email = "leaver-" + UUID.randomUUID().toString().substring(0, 8) + "@dki.local";
        String created = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "fullName", "Leaver", "password", "User@1234", "role", "USER"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID leaverId = UUID.fromString(objectMapper.readTree(created).get("id").asText());
        jdbc.update("""
                INSERT INTO user_google_accounts (user_id, google_email, refresh_token_enc, status, connected_at)
                VALUES (?, 'leaver@gmail.com', ?, 'CONNECTED', now())""", leaverId, tokenCipher.encrypt("rt-leaver"));

        mockMvc.perform(patch("/api/v1/users/" + leaverId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_google_accounts WHERE user_id = ?",
                Integer.class, leaverId)).isZero();
        assertThat(exchanger.lastRevoked.get()).isEqualTo("rt-leaver");
    }
}
