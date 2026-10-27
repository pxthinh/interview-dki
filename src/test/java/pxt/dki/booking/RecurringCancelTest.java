package pxt.dki.booking;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cancelling a series releases its remaining occurrences; finished ones stay as they were. */
@org.springframework.test.context.TestPropertySource(properties = "app.calendar.enabled=true")
class RecurringCancelTest extends AbstractIntegrationTest {

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
                VALUES (?, ?, 'F2', 10, true, 'cal-reccancel@test', now(), now())""",
                room, "RecCancel-" + UUID.randomUUID());
        day = TestData.nextWeekday();
    }

    private JsonNode createSeries(int weeks) throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings/recurring")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Weekly sync","startTime":"%s","endTime":"%s","weeks":%d}"""
                                .formatted(room, TestData.at(day, 9, 0), TestData.at(day, 10, 0), weeks)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void ownerCancelsSeries_remainingOccurrencesCancelled_pastOneUntouched() throws Exception {
        JsonNode series = createSeries(3);
        String seriesId = series.get("seriesId").asText();

        // a finished occurrence of the same series, inserted directly (API rejects past times)
        UUID pastId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status, series_id,
                                      created_at, updated_at)
                VALUES (?, ?, (SELECT id FROM users WHERE email = ?), 'Weekly sync', ?, ?, 'ACTIVE', ?::uuid,
                        now(), now())""",
                pastId, room, TestAuth.ALICE,
                java.sql.Timestamp.from(now.minus(Duration.ofHours(3))),
                java.sql.Timestamp.from(now.minus(Duration.ofHours(2))),
                seriesId);

        String body = mockMvc.perform(post("/api/v1/bookings/series/" + seriesId + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode cancelled = objectMapper.readTree(body);

        assertThat(cancelled).hasSize(3);
        cancelled.forEach(b -> assertThat(b.get("status").asText()).isEqualTo("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, pastId))
                .isEqualTo("ACTIVE");
        Integer cancelRows = jdbc.queryForObject("""
                SELECT count(*) FROM calendar_outbox o JOIN bookings b ON b.id = o.booking_id
                WHERE b.series_id = ?::uuid AND o.action = 'CANCEL'""", Integer.class, seriesId);
        assertThat(cancelRows).isEqualTo(3);
        Integer stillActiveFuture = jdbc.queryForObject("""
                SELECT count(*) FROM bookings WHERE series_id = ?::uuid AND status = 'ACTIVE'
                  AND end_time > now()""", Integer.class, seriesId);
        assertThat(stillActiveFuture).isZero();
    }

    @Test
    void individuallyCancelledOccurrence_staysAsItWas_seriesCancelSucceeds() throws Exception {
        JsonNode series = createSeries(2);
        String seriesId = series.get("seriesId").asText();
        String firstId = series.get("created").get(0).get("id").asText();

        mockMvc.perform(post("/api/v1/bookings/" + firstId + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        String firstCancelledAt = jdbc.queryForObject(
                "SELECT cancelled_at::text FROM bookings WHERE id = ?::uuid", String.class, firstId);

        String body = mockMvc.perform(post("/api/v1/bookings/series/" + seriesId + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body)).hasSize(1); // only the remaining occurrence
        assertThat(jdbc.queryForObject(
                "SELECT cancelled_at::text FROM bookings WHERE id = ?::uuid", String.class, firstId))
                .isEqualTo(firstCancelledAt);
    }

    @Test
    void otherUser_403_unknownSeries_404() throws Exception {
        JsonNode series = createSeries(2);
        String seriesId = series.get("seriesId").asText();

        mockMvc.perform(post("/api/v1/bookings/series/" + seriesId + "/cancel")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/bookings/series/" + UUID.randomUUID() + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound());
    }
}
