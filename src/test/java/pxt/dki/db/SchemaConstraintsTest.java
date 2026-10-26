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
    void adjacentAndCancelled_allowed() {
        UUID user = insertUser("u2@test.local");
        UUID room = insertRoom("Room B");
        insertBooking(room, user, "2026-11-03T02:00:00Z", "2026-11-03T03:00:00Z", "ACTIVE");

        assertThatCode(() -> {
            // adjacent: [02,03) + [03,04) must not conflict (half-open range)
            insertBooking(room, user, "2026-11-03T03:00:00Z", "2026-11-03T04:00:00Z", "ACTIVE");
            // same slot as the first one but CANCELLED must not conflict
            insertBooking(room, user, "2026-11-03T02:00:00Z", "2026-11-03T03:00:00Z", "CANCELLED");
        }).doesNotThrowAnyException();
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
