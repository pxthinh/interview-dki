package pxt.dki.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaConstraintsTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void overlappingActiveBookings_rejectedByExclusionConstraint() {
        UUID user = insertUser("u1@test.local");
        UUID room = insertRoom("Room A");
        insertBooking(room, user, "2026-11-03T02:00:00Z", "2026-11-03T03:00:00Z", "ACTIVE");

        assertThatThrownBy(() ->
                insertBooking(room, user, "2026-11-03T02:30:00Z", "2026-11-03T03:30:00Z", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bookings_no_overlap");
    }

    @Test
    void gapRespectingAndCancelled_allowed_backToBackRejected() {
        UUID user = insertUser("u2@test.local");
        UUID room = insertRoom("Room B");
        insertBooking(room, user, "2026-11-03T02:00:00Z", "2026-11-03T03:00:00Z", "ACTIVE");

        // back-to-back violates the 15-minute turnover break baked into the constraint
        assertThatThrownBy(() ->
                insertBooking(room, user, "2026-11-03T03:00:00Z", "2026-11-03T04:00:00Z", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bookings_no_overlap");

        assertThatCode(() -> {
            // 15 minutes after the previous end -> allowed
            insertBooking(room, user, "2026-11-03T03:15:00Z", "2026-11-03T04:15:00Z", "ACTIVE");
            // same slot as the first one but CANCELLED must not conflict
            insertBooking(room, user, "2026-11-03T02:00:00Z", "2026-11-03T03:00:00Z", "CANCELLED");
        }).doesNotThrowAnyException();
    }

    @Test
    void noShowStatus_andCheckedInAt_acceptedBySchema() {
        UUID user = insertUser("u3@test.local");
        UUID room = insertRoom("Room C");

        assertThatCode(() -> jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status,
                                      checked_in_at, created_at, updated_at)
                VALUES (?, ?, ?, 'Meeting', ?, ?, 'NO_SHOW', now(), now(), now())""",
                UUID.randomUUID(), room, user,
                java.sql.Timestamp.from(Instant.parse("2026-11-03T02:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-03T03:00:00Z"))))
                .doesNotThrowAnyException();
    }

    @Test
    void userGoogleAccounts_rejectsUnknownStatus() {
        UUID user = insertUser("g1@test.local");
        assertThatCode(() -> jdbc.update("""
                INSERT INTO user_google_accounts (user_id, google_email, refresh_token_enc, status, connected_at)
                VALUES (?, 'g1@gmail.com', 'enc', 'CONNECTED', now())""", user))
                .doesNotThrowAnyException();
        UUID user2 = insertUser("g2@test.local");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO user_google_accounts (user_id, google_email, refresh_token_enc, status, connected_at)
                VALUES (?, 'g2@gmail.com', 'enc', 'WEIRD', now())""", user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bookingUserEvents_rejectsDuplicatePair() {
        UUID user = insertUser("g3@test.local");
        UUID room = insertRoom("Room G");
        UUID booking = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Meeting', ?, ?, 'ACTIVE', now(), now())""",
                booking, room, user,
                java.sql.Timestamp.from(Instant.parse("2026-11-04T02:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-04T03:00:00Z")));

        assertThatCode(() -> jdbc.update("""
                INSERT INTO booking_user_events (booking_id, user_id, google_event_id, created_at)
                VALUES (?, ?, 'gev-x', now())""", booking, user))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO booking_user_events (booking_id, user_id, google_event_id, created_at)
                VALUES (?, ?, 'gev-y', now())""", booking, user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void calendarOutbox_acceptsTargetUserId() {
        UUID user = insertUser("g4@test.local");
        UUID room = insertRoom("Room H");
        UUID booking = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Meeting', ?, ?, 'ACTIVE', now(), now())""",
                booking, room, user,
                java.sql.Timestamp.from(Instant.parse("2026-11-05T02:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-05T03:00:00Z")));

        assertThatCode(() -> jdbc.update("""
                INSERT INTO calendar_outbox (booking_id, action, status, attempts, next_attempt_at, created_at, target_user_id)
                VALUES (?, 'CREATE', 'PENDING', 0, now(), now(), ?)""", booking, user))
                .doesNotThrowAnyException();
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, active, created_at, updated_at)
                VALUES (?, ?, 'x', 'Test User', 'USER', true, now(), now())""", id, email);
        return id;
    }

    private UUID insertRoom(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F1', 10, true, now(), now())""", id, name);
        return id;
    }

    private void insertBooking(UUID room, UUID user, String start, String end, String status) {
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Meeting', ?, ?, ?, now(), now())""",
                UUID.randomUUID(), room, user,
                java.sql.Timestamp.from(Instant.parse(start)),
                java.sql.Timestamp.from(Instant.parse(end)),
                status);
    }
}
