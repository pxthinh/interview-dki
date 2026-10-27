package pxt.dki.booking;

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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Weekly recurring bookings: one call creates an occurrence per week, skipping slots that
 * conflict (partial success) and reporting them. Calendar sync is enabled to assert one outbox
 * CREATE per created occurrence; rooms are inserted via JDBC with a calendar id so no
 * provisioning happens.
 */
@TestPropertySource(properties = "app.calendar.enabled=true")
class RecurringBookingTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String alice;
    private String bob;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        bob = TestAuth.token(mockMvc, objectMapper, TestAuth.BOB, TestAuth.PASSWORD_USER);
        room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, google_calendar_id, created_at, updated_at)
                VALUES (?, ?, 'F2', 10, true, 'cal-rec@test', now(), now())""",
                room, "Rec-" + UUID.randomUUID());
        day = TestData.nextWeekday();
    }

    private String recurringJson(int weeks, int sh, int eh) {
        return """
                {"roomId":"%s","title":"Weekly standup","startTime":"%s","endTime":"%s","weeks":%d}"""
                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0), weeks);
    }

    private JsonNode postRecurring(String token, String json, int expectStatus) throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings/recurring")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String bookSingle(String token, LocalDate d, int sh, int eh) throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Blocker","startTime":"%s","endTime":"%s"}"""
                                .formatted(room, TestData.at(d, sh, 0), TestData.at(d, eh, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void freeRoom_createsOneBookingPerWeek_sharingOneSeries() throws Exception {
        JsonNode r = postRecurring(alice, recurringJson(4, 9, 10), 201);

        assertThat(r.get("seriesId").asText()).isNotEmpty();
        assertThat(r.get("created")).hasSize(4);
        assertThat(r.get("skipped")).isEmpty();
        String seriesId = r.get("seriesId").asText();
        r.get("created").forEach(b -> assertThat(b.get("seriesId").asText()).isEqualTo(seriesId));

        // one outbox CREATE per occurrence
        Integer creates = jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox o JOIN bookings b ON b.id = o.booking_id
                WHERE b.series_id = ?::uuid AND o.action = 'CREATE'""", Integer.class, seriesId);
        assertThat(creates).isEqualTo(4);
    }

    @Test
    void occupiedWeek_isSkippedAndReported_othersCreated() throws Exception {
        String blocker = bookSingle(bob, day.plusDays(7), 9, 10);

        JsonNode r = postRecurring(alice, recurringJson(3, 9, 10), 201);

        assertThat(r.get("created")).hasSize(2);
        assertThat(r.get("skipped")).hasSize(1);
        JsonNode skipped = r.get("skipped").get(0);
        assertThat(skipped.get("conflict").get("bookingId").asText()).isEqualTo(blocker);
    }

    @Test
    void bufferOnlyConflict_isSkipped_not500() throws Exception {
        // blocker ends exactly when the week-3 occurrence starts; the 15-minute buffer bites
        bookSingle(bob, day.plusDays(14), 8, 9);

        JsonNode r = postRecurring(alice, recurringJson(3, 9, 10), 201);

        assertThat(r.get("created")).hasSize(2);
        assertThat(r.get("skipped")).hasSize(1);
    }

    @Test
    void everyWeekOccupied_422_RECURRING_ALL_CONFLICT_noSeriesPersisted() throws Exception {
        bookSingle(bob, day, 9, 10);
        bookSingle(bob, day.plusDays(7), 9, 10);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM recurring_series", Integer.class);

        JsonNode r = postRecurring(alice, recurringJson(2, 9, 10), 422);
        assertThat(r.get("errorCode").asText()).isEqualTo("RECURRING_ALL_CONFLICT");

        Integer after = jdbc.queryForObject("SELECT count(*) FROM recurring_series", Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void thirteenWeeks_within90Days_created_fourteen_422_TOO_FAR_AHEAD() throws Exception {
        JsonNode ok = postRecurring(alice, recurringJson(13, 11, 12), 201);
        assertThat(ok.get("created")).hasSize(13);

        JsonNode far = postRecurring(alice, recurringJson(14, 13, 14), 422);
        assertThat(far.get("errorCode").asText()).isEqualTo("TOO_FAR_AHEAD");
    }
}
