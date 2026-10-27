package pxt.dki.calendar;

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
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;
import pxt.dki.integrations.TokenCipher;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Processor pushes personal copies with per-user credentials. Room (Service Account) sync stays
 * DISABLED: this is also the personal-only mode test - the poll gate must still process rows.
 */
@TestPropertySource(properties = {
        "app.google-oauth.enabled=true",
        "app.google-oauth.client-id=test-client",
        "app.google-oauth.client-secret=test-secret",
        "app.google-oauth.redirect-uri=http://localhost/cb",
        "app.google-oauth.token-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
})
@Import(PersonalCalendarProcessorTest.FakePersonalConfig.class)
class PersonalCalendarProcessorTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakePersonalConfig {
        @Bean
        @Primary
        FakePersonalCalendarClient fakePersonalCalendarClient() {
            return new FakePersonalCalendarClient();
        }
    }

    @Autowired
    FakePersonalCalendarClient fake;

    @Autowired
    CalendarOutboxProcessor processor;

    @Autowired
    TokenCipher tokenCipher;

    @Autowired
    JdbcTemplate jdbc;

    private String alice;
    private UUID aliceId;
    private UUID bobId;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        // shared database: retire rows other test classes left PENDING so drain() only sees ours
        jdbc.update("UPDATE calendar_outbox SET status = 'DONE', processed_at = now() WHERE status = 'PENDING'");
        fake.calls.clear();
        fake.events.clear();
        fake.revokedToken = null;
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        aliceId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.ALICE);
        bobId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.BOB);
        jdbc.update("DELETE FROM user_google_accounts WHERE user_id IN (?, ?)", aliceId, bobId);
        connect(aliceId, "rt-alice");
        connect(bobId, "rt-bob");
        room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F9', 10, true, now(), now())""", room, "PProc-" + UUID.randomUUID());
        day = TestData.nextWeekday();
    }

    private void connect(UUID userId, String refreshToken) {
        jdbc.update("""
                INSERT INTO user_google_accounts (user_id, google_email, refresh_token_enc, status, connected_at)
                VALUES (?, 'p@gmail.com', ?, 'CONNECTED', now())""", userId, tokenCipher.encrypt(refreshToken));
    }

    private String createBooking(int sh, int eh, UUID... participants) throws Exception {
        String participantJson = participants.length == 0 ? "[]"
                : "[\"" + String.join("\",\"",
                        java.util.Arrays.stream(participants).map(UUID::toString).toList()) + "\"]";
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"PProc","startTime":"%s","endTime":"%s",
                                 "participantIds":%s}"""
                                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0), participantJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void drain() {
        // a few passes: deferred rows (update-before-create) need a second claim
        for (int i = 0; i < 3; i++) {
            processor.processBatch();
        }
    }

    private String mapping(String bookingId, UUID userId) {
        var list = jdbc.queryForList("""
                SELECT google_event_id FROM booking_user_events
                WHERE booking_id = ?::uuid AND user_id = ?""", String.class, bookingId, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    private String outboxState(String bookingId, UUID targetUser, String action) {
        return jdbc.queryForObject("""
                SELECT status FROM calendar_outbox
                WHERE booking_id = ?::uuid AND target_user_id = ? AND action = ?
                ORDER BY id DESC LIMIT 1""", String.class, bookingId, targetUser, action);
    }

    @Test
    void pushesCopy_savesMapping_roomColumnUntouched() throws Exception {
        String id = createBooking(9, 10, bobId);
        drain();

        assertThat(mapping(id, aliceId)).isNotNull();
        assertThat(mapping(id, bobId)).isNotNull();
        assertThat(outboxState(id, aliceId, "CREATE")).isEqualTo("DONE");
        assertThat(fake.calls).anyMatch(c -> c.startsWith("create:rt-alice:"))
                .anyMatch(c -> c.startsWith("create:rt-bob:"));
        assertThat(jdbc.queryForObject("SELECT google_event_id FROM bookings WHERE id = ?::uuid",
                String.class, id)).isNull();
    }

    @Test
    void update_patchesMappedPersonalEvent() throws Exception {
        String id = createBooking(11, 12);
        drain();
        String eventId = mapping(id, aliceId);

        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"PProc v2","startTime":"%s","endTime":"%s"}"""
                                .formatted(TestData.at(day, 11, 0), TestData.at(day, 12, 0))))
                .andExpect(status().isOk());
        drain();

        assertThat(outboxState(id, aliceId, "UPDATE")).isEqualTo("DONE");
        assertThat(fake.calls).contains("update:rt-alice:" + eventId);
        assertThat(fake.events.get("rt-alice:" + eventId).summary()).isEqualTo("PProc v2");
    }

    @Test
    void cancel_deletesMappedEvent() throws Exception {
        String id = createBooking(13, 14);
        drain();
        String eventId = mapping(id, aliceId);

        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        drain();

        assertThat(outboxState(id, aliceId, "CANCEL")).isEqualTo("DONE");
        assertThat(fake.calls).contains("delete:rt-alice:" + eventId);
        assertThat(mapping(id, aliceId)).isNull(); // copy gone -> mapping gone
    }

    private void setParticipants(String bookingId, int sh, int eh, UUID... participants) throws Exception {
        String participantJson = participants.length == 0 ? "[]"
                : "[\"" + String.join("\",\"",
                        java.util.Arrays.stream(participants).map(UUID::toString).toList()) + "\"]";
        mockMvc.perform(put("/api/v1/bookings/" + bookingId)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"PProc","startTime":"%s","endTime":"%s","participantIds":%s}"""
                                .formatted(TestData.at(day, sh, 0), TestData.at(day, eh, 0), participantJson)))
                .andExpect(status().isOk());
    }

    @Test
    void removedThenReAdded_participantGetsFreshLiveCopy() throws Exception {
        String id = createBooking(9, 10, bobId);
        drain();
        setParticipants(id, 9, 10);           // remove bob
        drain();
        setParticipants(id, 9, 10, bobId);    // re-add bob
        drain();

        String bobEvent = mapping(id, bobId);
        assertThat(bobEvent).isNotNull();
        assertThat(fake.events).containsKey("rt-bob:" + bobEvent); // a live event, not a deleted one
    }

    @Test
    void removedBeforeCopyPushed_noGhostCopy() throws Exception {
        String id = createBooking(11, 12, bobId); // bob's CREATE row still pending
        setParticipants(id, 11, 12);              // bob removed before any poll
        drain();

        assertThat(mapping(id, bobId)).isNull();
        assertThat(fake.calls).noneMatch(c -> c.startsWith("create:rt-bob:"));
    }

    @Test
    void removeAndReAddInOneBatch_singleCopy() throws Exception {
        String id = createBooking(13, 14, bobId);
        setParticipants(id, 13, 14);
        setParticipants(id, 13, 14, bobId);
        drain();

        assertThat(fake.calls.stream().filter(c -> c.startsWith("create:rt-bob:")).count()).isEqualTo(1);
        assertThat(mapping(id, bobId)).isNotNull();
    }

    @Test
    void unreadableStoredToken_failsThatUserOnly_batchContinues() throws Exception {
        jdbc.update("UPDATE user_google_accounts SET refresh_token_enc = 'bm90LWEtdmFsaWQtY2lwaGVydGV4dA==' "
                + "WHERE user_id = ?", aliceId);
        String id = createBooking(15, 16, bobId);
        drain(); // must not throw / freeze the outbox

        assertThat(outboxState(id, aliceId, "CREATE")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM user_google_accounts WHERE user_id = ?", String.class, aliceId))
                .isEqualTo("EXPIRED");
        assertThat(outboxState(id, bobId, "CREATE")).isEqualTo("DONE");
        assertThat(mapping(id, bobId)).isNotNull();
    }

    @Test
    void duplicateCreate_withExistingMapping_noSecondCopy() throws Exception {
        String id = createBooking(15, 16);
        drain();
        fake.calls.clear();

        // duplicate CREATE row (e.g. a retry/race leftover)
        jdbc.update("""
                INSERT INTO calendar_outbox (booking_id, action, status, attempts, next_attempt_at, created_at, target_user_id)
                VALUES (?::uuid, 'CREATE', 'PENDING', 0, now(), now(), ?)""", id, aliceId);
        drain();

        assertThat(outboxState(id, aliceId, "CREATE")).isEqualTo("DONE");
        assertThat(fake.calls).noneMatch(c -> c.startsWith("create:"));
    }

    @Test
    void updateWithoutMapping_reconcilesByCreatingTheCopy() throws Exception {
        String id = createBooking(17, 18);
        drain();
        jdbc.update("DELETE FROM booking_user_events WHERE booking_id = ?::uuid", id);

        jdbc.update("""
                INSERT INTO calendar_outbox (booking_id, action, status, attempts, next_attempt_at, created_at, target_user_id)
                VALUES (?::uuid, 'UPDATE', 'PENDING', 0, now(), now(), ?)""", id, aliceId);
        drain();

        assertThat(outboxState(id, aliceId, "UPDATE")).isEqualTo("DONE");
        assertThat(mapping(id, aliceId)).isNotNull(); // desired copy missing -> created
    }

    @Test
    void invalidGrant_marksAccountExpired_failsRowTerminally_othersContinue() throws Exception {
        fake.revokedToken = "rt-alice";
        String id = createBooking(19, 20, bobId);
        drain();

        assertThat(outboxState(id, aliceId, "CREATE")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM user_google_accounts WHERE user_id = ?", String.class, aliceId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT attempts FROM calendar_outbox
                WHERE booking_id = ?::uuid AND target_user_id = ? AND action = 'CREATE'""",
                Integer.class, id, aliceId)).isLessThanOrEqualTo(1); // terminal, no retry storm
        // bob unaffected
        assertThat(outboxState(id, bobId, "CREATE")).isEqualTo("DONE");
        assertThat(mapping(id, bobId)).isNotNull();
    }

    @Test
    void expiredAccountRow_completesAsNotConnected() throws Exception {
        String id = createBooking(7, 8); // row enqueued while CONNECTED
        jdbc.update("UPDATE user_google_accounts SET status = 'EXPIRED' WHERE user_id = ?", aliceId);
        drain();
        assertThat(outboxState(id, aliceId, "CREATE")).isEqualTo("DONE"); // skipped, no push
        assertThat(mapping(id, aliceId)).isNull();
    }
}
