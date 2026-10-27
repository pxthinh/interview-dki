package pxt.dki.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;
import pxt.dki.booking.BookingService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outbox fan-out for personal calendar copies: one row per connected recipient (organizer +
 * participants), independent of the room having a Google calendar. The Service Account sync
 * stays DISABLED here (app.calendar.enabled defaults false) - personal rows must appear anyway.
 */
@TestPropertySource(properties = {
        "app.google-oauth.enabled=true",
        "app.google-oauth.client-id=test-client",
        "app.google-oauth.client-secret=test-secret",
        "app.google-oauth.redirect-uri=http://localhost/cb",
        "app.google-oauth.token-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
})
class PersonalCalendarFanoutTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BookingService bookingService;

    private String alice;
    private UUID aliceId;
    private UUID bobId;
    private UUID carolId;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        aliceId = userId(TestAuth.ALICE);
        bobId = userId(TestAuth.BOB);
        carolId = userId("carol@dki.local");
        jdbc.update("DELETE FROM user_google_accounts WHERE user_id IN (?, ?, ?)", aliceId, bobId, carolId);
        connect(aliceId, "CONNECTED");
        connect(bobId, "CONNECTED");
        // carol stays unconnected
        room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F8', 10, true, now(), now())""", room, "Fanout-" + UUID.randomUUID());
        day = TestData.nextWeekday();
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private void connect(UUID userId, String status) {
        jdbc.update("""
                INSERT INTO user_google_accounts (user_id, google_email, refresh_token_enc, status, connected_at)
                VALUES (?, 'p@gmail.com', 'enc', ?, now())""", userId, status);
    }

    private String createBooking(int sh, int eh, UUID... participants) throws Exception {
        String participantJson = participants.length == 0 ? "[]"
                : "[\"" + String.join("\",\"",
                        java.util.Arrays.stream(participants).map(UUID::toString).toList()) + "\"]";
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Fanout","startTime":"%s","endTime":"%s",
                                 "participantIds":%s}"""
                                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0), participantJson)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private int rows(String bookingId, UUID targetUser, String action) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox
                WHERE booking_id = ?::uuid AND target_user_id = ? AND action = ?""",
                Integer.class, bookingId, targetUser, action);
    }

    private int roomRows(String bookingId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox WHERE booking_id = ?::uuid AND target_user_id IS NULL""",
                Integer.class, bookingId);
    }

    @Test
    void create_fansOutToConnectedOrganizerAndParticipants_evenWithoutRoomCalendar() throws Exception {
        String id = createBooking(9, 10, bobId, carolId);

        assertThat(rows(id, aliceId, "CREATE")).isEqualTo(1);
        assertThat(rows(id, bobId, "CREATE")).isEqualTo(1);
        assertThat(rows(id, carolId, "CREATE")).isZero(); // not connected
        assertThat(roomRows(id)).isZero(); // SA sync off + room has no calendar
    }

    @Test
    void expiredAccount_skipped() throws Exception {
        jdbc.update("UPDATE user_google_accounts SET status = 'EXPIRED' WHERE user_id = ?", bobId);
        String id = createBooking(11, 12, bobId);
        assertThat(rows(id, bobId, "CREATE")).isZero();
        assertThat(rows(id, aliceId, "CREATE")).isEqualTo(1);
    }

    @Test
    void cancel_andNoShow_fanOutCancelRows() throws Exception {
        String id = createBooking(13, 14, bobId);
        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        assertThat(rows(id, aliceId, "CANCEL")).isEqualTo(1);
        assertThat(rows(id, bobId, "CANCEL")).isEqualTo(1);

        // no-show: a running unclaimed booking inserted directly, released via markNoShow
        UUID noShowId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status, created_at, updated_at)
                VALUES (?, ?, ?, 'NoShow', ?, ?, 'ACTIVE', now(), now())""",
                noShowId, room, aliceId,
                java.sql.Timestamp.from(now.minus(Duration.ofMinutes(20))),
                java.sql.Timestamp.from(now.plus(Duration.ofMinutes(40))));
        bookingService.markNoShow(noShowId);
        assertThat(rows(noShowId.toString(), aliceId, "CANCEL")).isEqualTo(1);
    }

    @Test
    void recurringSeries_fansOutPerOccurrence() throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings/recurring")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Weekly","startTime":"%s","endTime":"%s","weeks":2}"""
                                .formatted(room, TestData.at(day, 15, 0), TestData.at(day, 16, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body).get("created");
        assertThat(created).hasSize(2);
        for (JsonNode b : created) {
            assertThat(rows(b.get("id").asText(), aliceId, "CREATE")).isEqualTo(1);
        }
    }

    @Test
    void adminToggleOff_suppressesPersonalRows() throws Exception {
        jdbc.update("UPDATE app_settings SET value = 'false' WHERE key = 'calendar.sync-enabled'");
        try {
            String id = createBooking(17, 18, bobId);
            assertThat(rows(id, aliceId, "CREATE")).isZero();
            assertThat(rows(id, bobId, "CREATE")).isZero();
        } finally {
            jdbc.update("UPDATE app_settings SET value = 'true' WHERE key = 'calendar.sync-enabled'");
        }
    }

    @Test
    void update_participantDelta_createForAdded_cancelForRemoved_updateForKept() throws Exception {
        connect(carolId, "CONNECTED");
        String id = createBooking(8, 9, bobId); // alice organizer, bob participant

        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Fanout v2","startTime":"%s","endTime":"%s",
                                 "participantIds":["%s"]}"""
                                .formatted(TestData.at(day, 8, 0), TestData.at(day, 9, 0), carolId)))
                .andExpect(status().isOk());

        assertThat(rows(id, carolId, "CREATE")).isEqualTo(1);  // added
        assertThat(rows(id, bobId, "CANCEL")).isEqualTo(1);    // removed
        assertThat(rows(id, aliceId, "UPDATE")).isEqualTo(1);  // kept (organizer)
        assertThat(rows(id, carolId, "UPDATE")).isZero();      // added gets CREATE only
        // CREATE row id of the added user precedes the UPDATE rows (batch converges in order)
        Long createId = jdbc.queryForObject("""
                SELECT id FROM calendar_outbox WHERE booking_id = ?::uuid AND target_user_id = ? AND action = 'CREATE'
                ORDER BY id DESC LIMIT 1""", Long.class, id, carolId);
        Long updateId = jdbc.queryForObject("""
                SELECT id FROM calendar_outbox WHERE booking_id = ?::uuid AND target_user_id = ? AND action = 'UPDATE'
                ORDER BY id DESC LIMIT 1""", Long.class, id, aliceId);
        assertThat(createId).isLessThan(updateId);
    }
}
