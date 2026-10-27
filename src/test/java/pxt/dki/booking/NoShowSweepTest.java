package pxt.dki.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The no-show sweeper releases ACTIVE bookings that were never checked in within the grace
 * period (15 minutes in tests/default). Bookings are inserted via JDBC: they must already be
 * running, which the API forbids. Calendar sync is enabled so the release enqueues a CANCEL.
 */
@TestPropertySource(properties = "app.calendar.enabled=true")
class NoShowSweepTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NoShowSweeper sweeper;

    @Autowired
    BookingService bookingService;

    private UUID alice;
    private UUID room;

    @BeforeEach
    void setUp() {
        alice = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.ALICE);
        room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, google_calendar_id, created_at, updated_at)
                VALUES (?, ?, 'F1', 10, true, 'cal-sweep@test', now(), now())""",
                room, "Sweep-" + UUID.randomUUID());
    }

    @Test
    void unclaimedBooking_pastGrace_becomesNoShow_andEnqueuesCancel() {
        UUID id = insertBooking(minusMinutes(20), plusMinutes(40), null, "ACTIVE");

        sweeper.sweep();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        Integer cancels = jdbc.queryForObject(
                "SELECT count(*) FROM calendar_outbox WHERE booking_id = ? AND action = 'CANCEL'",
                Integer.class, id);
        assertThat(cancels).isEqualTo(1);
    }

    @Test
    void checkedInBooking_untouched() {
        UUID id = insertBooking(minusMinutes(20), plusMinutes(40), minusMinutes(18), "ACTIVE");
        sweeper.sweep();
        assertThat(status(id)).isEqualTo("ACTIVE");
    }

    @Test
    void meetingShorterThanGrace_untouched() {
        // 15-minute meeting: grace deadline would land at/after its end -> never swept
        UUID id = insertBooking(minusMinutes(16), minusMinutes(1), null, "ACTIVE");
        sweeper.sweep();
        assertThat(status(id)).isEqualTo("ACTIVE");
    }

    @Test
    void markNoShow_onCancelledBooking_isNoOp() {
        UUID id = insertBooking(minusMinutes(20), plusMinutes(40), null, "CANCELLED");
        bookingService.markNoShow(id);
        assertThat(status(id)).isEqualTo("CANCELLED");
    }

    @Test
    void freedSlot_isBookableAgain_constraintIgnoresNoShow() {
        UUID id = insertBooking(minusMinutes(20), plusMinutes(40), null, "ACTIVE");
        sweeper.sweep();
        assertThat(status(id)).isEqualTo("NO_SHOW");

        // same room, same slot, ACTIVE: the exclusion constraint must not fire
        assertThatCode(() -> insertBooking(minusMinutes(20), plusMinutes(40), null, "ACTIVE"))
                .doesNotThrowAnyException();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, id);
    }

    private UUID insertBooking(Instant start, Instant end, Instant checkedInAt, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status,
                                      checked_in_at, created_at, updated_at)
                VALUES (?, ?, ?, 'Standup', ?, ?, ?, ?, now(), now())""",
                id, room, alice,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), status,
                checkedInAt == null ? null : java.sql.Timestamp.from(checkedInAt));
        return id;
    }

    private Instant minusMinutes(long m) {
        return Instant.now().minus(Duration.ofMinutes(m));
    }

    private Instant plusMinutes(long m) {
        return Instant.now().plus(Duration.ofMinutes(m));
    }
}
