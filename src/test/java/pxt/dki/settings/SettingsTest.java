package pxt.dki.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Booking limits live in app_settings, edited by an admin. The shared test database is restored
 * to the defaults after every test so other classes keep their 90-day horizon.
 */
class SettingsTest extends AbstractIntegrationTest {

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
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F3', 10, true, now(), now())""", room, "Settings-" + UUID.randomUUID());
        day = TestData.nextWeekday();
    }

    @AfterEach
    void restoreDefaults() {
        jdbc.update("UPDATE app_settings SET value = '90' WHERE key = 'booking.max-days-ahead'");
        jdbc.update("UPDATE app_settings SET value = '26' WHERE key = 'booking.max-recurring-weeks'");
    }

    private void putSettings(String token, String json, int expect) throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(expect));
    }

    private org.springframework.test.web.servlet.ResultActions book(LocalDate d, int sh, int eh) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                .header("Authorization", "Bearer " + alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":"%s","title":"Limit test","startTime":"%s","endTime":"%s"}"""
                        .formatted(room, TestData.at(d, sh, 0), TestData.at(d, eh, 0))));
    }

    @Test
    void defaults_visibleToAnyUser() throws Exception {
        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDaysAhead").value(90))
                .andExpect(jsonPath("$.maxRecurringWeeks").value(26));
    }

    @Test
    void loweredHorizon_appliesToNewBookings() throws Exception {
        putSettings(admin, "{\"maxDaysAhead\":7}", 200);
        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.maxDaysAhead").value(7));

        book(day.plusDays(10), 9, 10)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TOO_FAR_AHEAD"));
        book(day, 9, 10).andExpect(status().isCreated());
    }

    @Test
    void loweredHorizon_doesNotBlockCancellingExistingFartherBooking() throws Exception {
        String body = book(day.plusDays(40), 11, 12)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        putSettings(admin, "{\"maxDaysAhead\":7}", 200);

        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
    }

    @Test
    void loweredWeeks_limitsRecurring() throws Exception {
        putSettings(admin, "{\"maxRecurringWeeks\":3}", 200);
        mockMvc.perform(post("/api/v1/bookings/recurring")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Weekly","startTime":"%s","endTime":"%s","weeks":4}"""
                                .formatted(room, TestData.at(day, 13, 0), TestData.at(day, 14, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("RECURRING_TOO_MANY_WEEKS"));
    }

    @Test
    void writeValidation_andAuthz() throws Exception {
        putSettings(alice, "{\"maxDaysAhead\":30}", 403);
        putSettings(admin, "{\"maxDaysAhead\":0}", 422);
        putSettings(admin, "{\"maxDaysAhead\":366}", 422);
        putSettings(admin, "{\"maxRecurringWeeks\":1}", 422);
        // nothing broken by the rejected writes
        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.maxDaysAhead").value(90));
    }
}
