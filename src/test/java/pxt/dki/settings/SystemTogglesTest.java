package pxt.dki.settings;

import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System-wide runtime toggles: calendar sync and mail, ANDed with the env switches. Calendar env
 * is on in this context so the admin toggle is the only gate; the shared DB is restored after
 * each test.
 */
@TestPropertySource(properties = "app.calendar.enabled=true")
class SystemTogglesTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private String alice;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
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

    private int outboxRows(String bookingId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_outbox WHERE booking_id = ?::uuid", Integer.class, bookingId);
    }

    @Test
    void defaults_bothTogglesOn() throws Exception {
        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarSyncEnabled").value(true))
                .andExpect(jsonPath("$.mailEnabled").value(true));
    }

    @Test
    void syncToggleOff_stopsOutboxWrites_onGatesThemAgain() throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarSyncEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarSyncEnabled").value(false));

        String silent = book(9, 10);
        assertThat(outboxRows(silent)).isZero();

        mockMvc.perform(put("/api/v1/admin/settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarSyncEnabled\":true}"))
                .andExpect(status().isOk());

        String synced = book(11, 12);
        assertThat(outboxRows(synced)).isEqualTo(1);
    }
}
