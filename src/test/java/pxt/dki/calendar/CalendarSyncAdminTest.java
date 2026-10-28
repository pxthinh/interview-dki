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
import pxt.dki.room.Room;
import pxt.dki.room.RoomRepository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/admin/calendar-outbox/sync-all: back-fills the outbox for bookings never pushed. */
@TestPropertySource(properties = "app.calendar.enabled=true")
@Import(CalendarSyncAdminTest.FakeClientConfig.class)
class CalendarSyncAdminTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeGoogleCalendarClient fakeGoogleCalendarClient() {
            return new FakeGoogleCalendarClient();
        }
    }

    @Autowired
    CalendarOutboxRepository outboxRepository;

    @Autowired
    RoomRepository roomRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private String alice;
    private String admin;
    private Room room;
    private LocalDate day;
    private int hour = 8;
    private long outboxHighWater;

    @BeforeEach
    void setUp() throws Exception {
        outboxHighWater = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM calendar_outbox", Long.class);
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        room = new Room();
        room.setName("SyncAll-" + UUID.randomUUID().toString().substring(0, 8));
        room.setCapacity(4);
        room.setGoogleCalendarId("cal-syncall-" + UUID.randomUUID().toString().substring(0, 8));
        room = roomRepository.save(room);
        day = TestData.nextWeekday().plusDays(70);
    }

    /**
     * sync-all enqueues rows for every unsynced booking in the shared test DB, not only this
     * class's. Left PENDING they would fill the processor batch of later test classes
     * (SystemTogglesTest polls once and expects its own row in that batch), so drop them.
     */
    @org.junit.jupiter.api.AfterEach
    void dropEnqueuedRows() {
        jdbc.update("DELETE FROM calendar_outbox WHERE id > ?", outboxHighWater);
    }

    /** Books the next hour in the test room and drops its outbox rows: "booked while sync was off". */
    private UUID bookUnsynced() throws Exception {
        int h = hour++;
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Unsynced","startTime":"%s","endTime":"%s"}"""
                                .formatted(room.getId(), TestData.at(day, h, 0), TestData.at(day, h, 45))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        jdbc.update("DELETE FROM calendar_outbox WHERE booking_id = ?", id);
        return id;
    }

    /** What sync-all must enqueue: upcoming ACTIVE, room has a calendar, never pushed, never queued. */
    private int expectedRoomCandidates() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM bookings b JOIN rooms r ON r.id = b.room_id
                WHERE b.status = 'ACTIVE' AND b.end_time > ? AND r.google_calendar_id IS NOT NULL
                  AND b.google_event_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM calendar_outbox o
                                  WHERE o.booking_id = b.id AND o.target_user_id IS NULL)""",
                Integer.class, Timestamp.from(clock.instant()));
    }

    @Test
    void user_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/calendar-outbox/sync-all")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_enqueuesEveryUnsyncedUpcomingBooking_onceOnly() throws Exception {
        UUID first = bookUnsynced();
        UUID second = bookUnsynced();
        int expected = expectedRoomCandidates();
        assertThat(expected).isGreaterThanOrEqualTo(2);

        mockMvc.perform(post("/api/v1/admin/calendar-outbox/sync-all")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueuedCount").value(expected));

        for (UUID id : new UUID[] {first, second}) {
            var rows = outboxRepository.findByBookingIdOrderById(id);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getAction()).isEqualTo(OutboxAction.CREATE);
            assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(rows.get(0).getTargetUserId()).isNull();
        }

        // second click: everything is queued already
        mockMvc.perform(post("/api/v1/admin/calendar-outbox/sync-all")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueuedCount").value(0));
    }

    @Test
    void bookingWithFailedRow_notReEnqueued() throws Exception {
        UUID id = bookUnsynced();
        CalendarOutbox failed = new CalendarOutbox();
        failed.setBookingId(id);
        failed.setAction(OutboxAction.CREATE);
        failed.setStatus(OutboxStatus.FAILED);
        failed.setNextAttemptAt(clock.instant());
        failed.setAttempts(6);
        outboxRepository.save(failed);
        int expected = expectedRoomCandidates(); // excludes the FAILED one

        mockMvc.perform(post("/api/v1/admin/calendar-outbox/sync-all")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueuedCount").value(expected));

        var rows = outboxRepository.findByBookingIdOrderById(id);
        assertThat(rows).hasSize(1); // FAILED rows are retried per row by the admin, not re-queued
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
