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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changes made directly on the ROOM calendar (imported) must reach personal copies too:
 * cancel / move / new event each enqueue personal rows. Room rows are never enqueued by the
 * import (that would echo back to Google).
 */
@TestPropertySource(properties = {
        "app.calendar.enabled=true",
        "app.google-oauth.enabled=true",
        "app.google-oauth.client-id=test-client",
        "app.google-oauth.client-secret=test-secret",
        "app.google-oauth.redirect-uri=http://localhost/cb",
        "app.google-oauth.token-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
})
@Import(PersonalCopyImportPropagationTest.FakeClientConfig.class)
class PersonalCopyImportPropagationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeGoogleCalendarClient fakeGoogleCalendarClient() {
            return new FakeGoogleCalendarClient();
        }
    }

    @Autowired
    FakeGoogleCalendarClient fake;

    @Autowired
    CalendarImportJob importJob;

    @Autowired
    TokenCipher tokenCipher;

    @Autowired
    JdbcTemplate jdbc;

    private String alice;
    private UUID aliceId;
    private UUID room;
    private String calId;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        aliceId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.ALICE);
        jdbc.update("DELETE FROM user_google_accounts WHERE user_id = ?", aliceId);
        jdbc.update("""
                INSERT INTO user_google_accounts (user_id, google_email, refresh_token_enc, status, connected_at)
                VALUES (?, 'alice@gmail.com', ?, 'CONNECTED', now())""", aliceId, tokenCipher.encrypt("rt-alice"));
        room = UUID.randomUUID();
        calId = "cal-prop-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, google_calendar_id, created_at, updated_at)
                VALUES (?, ?, 'F11', 10, true, ?, now(), now())""", room, "Prop-" + UUID.randomUUID(), calId);
        day = TestData.nextWeekday().plusDays(21);
    }

    private int personalRows(String bookingId, String action) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox
                WHERE booking_id = ?::uuid AND target_user_id = ? AND action = ?""",
                Integer.class, bookingId, aliceId, action);
    }

    private int roomRows(String bookingId, String action) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox
                WHERE booking_id = ?::uuid AND target_user_id IS NULL AND action = ?""",
                Integer.class, bookingId, action);
    }

    private String bookLinked(String eventId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Linked","startTime":"%s","endTime":"%s"}"""
                                .formatted(room, TestData.at(day, 9, 0), TestData.at(day, 10, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        // simulate the room outbox having pushed it
        jdbc.update("UPDATE bookings SET google_event_id = ? WHERE id = ?::uuid", eventId, id);
        return id;
    }

    @Test
    void roomCalendarMove_enqueuesPersonalUpdate_notRoomUpdate() throws Exception {
        String id = bookLinked("gev-prop-move");
        fake.addRemoteEvent(calId, "gev-prop-move", "Linked moved",
                TestData.at(day, 13, 0).toInstant(), TestData.at(day, 14, 0).toInstant(), TestAuth.ALICE);

        importJob.runOnce();

        assertThat(personalRows(id, "UPDATE")).isEqualTo(1);
        assertThat(roomRows(id, "UPDATE")).isZero(); // no echo back to the room calendar
    }

    @Test
    void roomCalendarCancel_enqueuesPersonalCancel() throws Exception {
        String id = bookLinked("gev-prop-cancel");
        fake.addRemoteEvent(calId, "gev-prop-cancel", "Linked",
                TestData.at(day, 9, 0).toInstant(), TestData.at(day, 10, 0).toInstant(), TestAuth.ALICE);
        importJob.runOnce(); // echo of our own push: no change
        fake.cancelRemoteEvent(calId, "gev-prop-cancel");

        importJob.runOnce();

        assertThat(personalRows(id, "CANCEL")).isEqualTo(1);
    }

    @Test
    void newRoomCalendarEvent_enqueuesPersonalCreateForMatchedOrganizer() {
        fake.addRemoteEvent(calId, "gev-prop-new", "Created on Google",
                TestData.at(day, 16, 0).toInstant(), TestData.at(day, 17, 0).toInstant(), TestAuth.ALICE);

        importJob.runOnce();

        String id = jdbc.queryForObject("SELECT id::text FROM bookings WHERE google_event_id = 'gev-prop-new'",
                String.class);
        assertThat(personalRows(id, "CREATE")).isEqualTo(1);
    }
}
