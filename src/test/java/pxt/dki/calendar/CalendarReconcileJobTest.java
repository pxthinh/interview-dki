package pxt.dki.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.booking.BookingRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bookings inserted straight into the database (seed script, SQL import) have no outbox row.
 * The reconcile job must pick them up so they still reach the room's Google Calendar.
 */
@TestPropertySource(properties = "app.calendar.enabled=true")
@Import(CalendarReconcileJobTest.FakeClientConfig.class)
class CalendarReconcileJobTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeGoogleCalendarClient fakeGoogleCalendarClient() {
            return new FakeGoogleCalendarClient();
        }
    }

    @Autowired
    CalendarReconcileJob job;

    @Autowired
    CalendarOutboxProcessor processor;

    @Autowired
    CalendarOutboxRepository outboxRepository;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    JdbcTemplate jdbc;

    private UUID insertViaSql(String roomName, int hoursFromNow) {
        UUID id = UUID.randomUUID();
        Instant start = Instant.now().plus(hoursFromNow, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO bookings (id, room_id, user_id, title, start_time, end_time, status,
                                      buffer_minutes, created_at, updated_at)
                SELECT ?, r.id, u.id, 'SQL seeded', ?, ?, 'ACTIVE', 0, now(), now()
                FROM rooms r, users u WHERE r.name = ? AND u.email = 'alice@dki.local'""",
                id, Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)), roomName);
        return id;
    }

    @Test
    void sqlInsertedBooking_isQueuedByReconcile_thenPushed() {
        UUID roomId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, google_calendar_id, created_at, updated_at)
                VALUES (?, ?, 'F1', 4, true, ?, now(), now())""",
                roomId, "Reconcile-" + roomId.toString().substring(0, 8), "cal-reconcile-" + roomId.toString().substring(0, 8));
        String roomName = jdbc.queryForObject("SELECT name FROM rooms WHERE id = ?", String.class, roomId);
        UUID booking = insertViaSql(roomName, 48);
        assertThat(outboxRepository.findByBookingIdOrderById(booking)).isEmpty();

        job.reconcile();

        var rows = outboxRepository.findByBookingIdOrderById(booking);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(OutboxAction.CREATE);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(rows.get(0).getTargetUserId()).isNull();

        job.reconcile(); // idempotent: nothing new while the row is queued
        assertThat(outboxRepository.findByBookingIdOrderById(booking)).hasSize(1);

        processor.processBatch();
        assertThat(outboxRepository.findByBookingIdOrderById(booking).get(0).getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(bookingRepository.findById(booking).orElseThrow().getGoogleEventId()).startsWith("evt-");

        job.reconcile(); // pushed bookings are left alone
        assertThat(outboxRepository.findByBookingIdOrderById(booking)).hasSize(1);
    }

    @Test
    void roomWithoutCalendar_isIgnored() {
        UUID roomId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, google_calendar_id, created_at, updated_at)
                VALUES (?, ?, 'F1', 4, true, NULL, now(), now())""",
                roomId, "NoCal-" + roomId.toString().substring(0, 8));
        String roomName = jdbc.queryForObject("SELECT name FROM rooms WHERE id = ?", String.class, roomId);
        UUID booking = insertViaSql(roomName, 72);

        job.reconcile();

        assertThat(outboxRepository.findByBookingIdOrderById(booking)).isEmpty();
    }
}
