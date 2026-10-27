package pxt.dki.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Check-in: booker or any participant confirms presence from 5 minutes before start until the
 * meeting ends. Bookings that must already be running are inserted via JDBC because the API
 * rejects past start times.
 */
class BookingCheckInTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String alice;
    private String bob;
    private UUID aliceId;
    private UUID bobId;
    private UUID room;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        bob = TestAuth.token(mockMvc, objectMapper, TestAuth.BOB, TestAuth.PASSWORD_USER);
        aliceId = userId(TestAuth.ALICE);
        bobId = userId(TestAuth.BOB);
        room = insertRoom("CheckIn-" + UUID.randomUUID());
    }

    @Test
    void booker_checksIn_duringMeeting_200() throws Exception {
        UUID id = insertBooking(aliceId, minusMinutes(2), plusMinutes(58), "ACTIVE");
        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedInAt").isNotEmpty());
    }

    @Test
    void participant_checksIn_200() throws Exception {
        UUID id = insertBooking(aliceId, minusMinutes(2), plusMinutes(58), "ACTIVE");
        addParticipant(id, bobId);
        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedInAt").isNotEmpty());
    }

    @Test
    void stranger_checksIn_403() throws Exception {
        UUID id = insertBooking(aliceId, minusMinutes(2), plusMinutes(58), "ACTIVE");
        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
    }

    @Test
    void justInsideEarlyWindow_200_wellBefore_422_CHECKIN_TOO_EARLY() throws Exception {
        // starts in just under 5 minutes: the window (start - 5min) is already open
        UUID edge = insertBooking(aliceId, plusSeconds(4 * 60 + 50), plusMinutes(60), "ACTIVE");
        mockMvc.perform(post("/api/v1/bookings/" + edge + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        // far enough from the first booking (plus its buffer) to clear the exclusion constraint
        UUID early = insertBooking(aliceId, plusMinutes(90), plusMinutes(150), "ACTIVE");
        mockMvc.perform(post("/api/v1/bookings/" + early + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CHECKIN_TOO_EARLY"));
    }

    @Test
    void endedMeeting_422_BOOKING_ALREADY_ENDED() throws Exception {
        UUID id = insertBooking(aliceId, minusMinutes(120), minusMinutes(60), "ACTIVE");
        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_ALREADY_ENDED"));
    }

    @Test
    void cancelledBooking_422_BOOKING_NOT_ACTIVE() throws Exception {
        UUID id = insertBooking(aliceId, minusMinutes(2), plusMinutes(58), "CANCELLED");
        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_ACTIVE"));
    }

    @Test
    void secondCheckIn_isIdempotent() throws Exception {
        UUID id = insertBooking(aliceId, minusMinutes(2), plusMinutes(58), "ACTIVE");
        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        Instant firstAt = jdbc.queryForObject(
                "SELECT checked_in_at FROM bookings WHERE id = ?", java.sql.Timestamp.class, id).toInstant();

        mockMvc.perform(post("/api/v1/bookings/" + id + "/check-in")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedInAt").isNotEmpty());
        Instant secondAt = jdbc.queryForObject(
                "SELECT checked_in_at FROM bookings WHERE id = ?", java.sql.Timestamp.class, id).toInstant();
        org.assertj.core.api.Assertions.assertThat(secondAt).isEqualTo(firstAt);
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private UUID insertRoom(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F1', 10, true, now(), now())""", id, name);
        return id;
    }

    private UUID insertBooking(UUID user, Instant start, Instant end, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Standup', ?, ?, ?, now(), now())""",
                id, room, user, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), status);
        return id;
    }

    private void addParticipant(UUID bookingId, UUID userId) {
        jdbc.update("INSERT INTO booking_participants (booking_id, user_id) VALUES (?, ?)", bookingId, userId);
    }

    private Instant minusMinutes(long m) {
        return Instant.now().minus(Duration.ofMinutes(m));
    }

    private Instant plusMinutes(long m) {
        return Instant.now().plus(Duration.ofMinutes(m));
    }

    private Instant plusSeconds(long s) {
        return Instant.now().plus(Duration.ofSeconds(s));
    }
}
