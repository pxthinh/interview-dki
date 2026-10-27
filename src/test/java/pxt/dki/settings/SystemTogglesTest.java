package pxt.dki.settings;

import org.junit.jupiter.api.AfterEach;
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
import pxt.dki.calendar.CalendarOutboxProcessor;
import pxt.dki.calendar.FakeGoogleCalendarClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System-wide runtime toggles. Calendar sync off = PAUSE: changes keep being queued and are
 * pushed once sync is back on (nothing is lost). Calendar env is on in this context; the shared
 * DB is restored after each test.
 */
@TestPropertySource(properties = "app.calendar.enabled=true")
@Import(SystemTogglesTest.FakeClientConfig.class)
class SystemTogglesTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeGoogleCalendarClient fakeGoogleCalendarClient() {
            return new FakeGoogleCalendarClient();
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeGoogleCalendarClient fake;

    @Autowired
    CalendarOutboxProcessor processor;

    private String admin;
    private String alice;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, google_calendar_id, created_at, updated_at)
                VALUES (?, ?, 'F6', 10, true, 'cal-toggle@test', now(), now())""",
                room, "Toggle-" + UUID.randomUUID());
        day = TestData.nextWeekday();
    }

    @AfterEach
    void restoreDefaults() {
        jdbc.update("UPDATE app_settings SET value = 'true' WHERE key IN ('calendar.sync-enabled', 'mail.enabled')");
    }

    private void setSync(boolean on) throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarSyncEnabled\":" + on + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarSyncEnabled").value(on));
    }

    private String book(int sh, int eh) throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Toggle test","startTime":"%s","endTime":"%s"}"""
                                .formatted(room, TestData.at(day, sh, 0), TestData.at(day, eh, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String roomRowStatus(String bookingId) {
        return jdbc.queryForObject("""
                SELECT status FROM calendar_outbox WHERE booking_id = ?::uuid AND target_user_id IS NULL
                ORDER BY id DESC LIMIT 1""", String.class, bookingId);
    }

    @Test
    void defaults_bothTogglesOn() throws Exception {
        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarSyncEnabled").value(true))
                .andExpect(jsonPath("$.mailEnabled").value(true));
    }

    @Test
    void pausedSync_queuesChanges_andPushesThemAfterResume() throws Exception {
        setSync(false);
        String id = book(9, 10);
        assertThat(roomRowStatus(id)).isEqualTo("PENDING"); // queued, not dropped

        processor.poll(); // paused: nothing goes to Google
        assertThat(roomRowStatus(id)).isEqualTo("PENDING");
        assertThat(fake.calls).noneMatch(c -> c.startsWith("create:cal-toggle@test"));

        setSync(true);
        processor.poll();
        assertThat(roomRowStatus(id)).isEqualTo("DONE");
        assertThat(fake.calls).anyMatch(c -> c.startsWith("create:cal-toggle@test"));
    }

    @Test
    void roomCreatedWhilePaused_stillGetsItsCalendar() throws Exception {
        setSync(false);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Paused-" + UUID.randomUUID() + "\",\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("googleCalendarId").asText()).startsWith("cal-");
    }

    @Test
    void roomUpdateWithoutAnyCreateRow_isPushedAsCreate_notDeferredForever() throws Exception {
        String id = book(11, 12);
        // the CREATE row is gone (e.g. booked while sync was off at env level, or it FAILED)
        jdbc.update("DELETE FROM calendar_outbox WHERE booking_id = ?::uuid", id);
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Toggle test v2","startTime":"%s","endTime":"%s"}"""
                                .formatted(TestData.at(day, 11, 0), TestData.at(day, 12, 0))))
                .andExpect(status().isOk());

        processor.processBatch();

        assertThat(roomRowStatus(id)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT google_event_id FROM bookings WHERE id = ?::uuid",
                String.class, id)).isNotNull();
    }
}
