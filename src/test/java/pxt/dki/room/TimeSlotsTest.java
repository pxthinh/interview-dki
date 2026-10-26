package pxt.dki.room;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /rooms/time-slots: valid start times on the 15-minute grid for a date + duration.
 * A slot where every matching room is taken (including the 15-minute break) is not returned.
 */
class TimeSlotsTest extends AbstractIntegrationTest {

    private String alice;
    private String admin;
    private UUID room;
    private LocalDate day;
    private String amenity;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        // a dedicated room matched by a per-test unique amenity so nothing else interferes
        amenity = "slot-" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Slot-" + UUID.randomUUID() + "\",\"capacity\":4,\"amenities\":[\"" + amenity + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        room = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        day = TestData.nextWeekday().plusDays(49);
    }

    private List<String> slotStarts(String query) throws Exception {
        String body = mockMvc.perform(get("/api/v1/rooms/time-slots?" + query)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> starts = new ArrayList<>();
        for (JsonNode n : objectMapper.readTree(body)) {
            assertThat(n.get("availableRooms").asInt()).isGreaterThan(0);
            starts.add(n.get("startTime").asText().substring(11, 16));
        }
        return starts;
    }

    @Test
    void fullDay_gridFrom7To19_for60Minutes() throws Exception {
        List<String> starts = slotStarts("date=" + day + "&durationMinutes=60&amenities=" + amenity);
        assertThat(starts).first().isEqualTo("07:00");
        assertThat(starts).last().isEqualTo("19:00"); // 19:00+60m = 20:00 closing
        assertThat(starts).hasSize(4 * 12 + 1); // 07:00..19:00 step 15m
        assertThat(starts).allMatch(s -> List.of("00", "15", "30", "45").contains(s.substring(3)));
    }

    @Test
    void bookedWindow_hiddenIncludingBreak() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Busy","startTime":"%s","endTime":"%s"}"""
                                .formatted(room, TestData.at(day, 9, 0), TestData.at(day, 9, 45))))
                .andExpect(status().isCreated());

        List<String> starts = slotStarts("date=" + day + "&durationMinutes=60&amenities=" + amenity);
        // booking 09:00-09:45 + 15m break on both sides blocks starts 08:00..09:45 for a 60m meeting
        assertThat(starts).contains("07:45", "10:00");
        assertThat(starts).doesNotContain("08:00", "08:30", "09:00", "09:45");
    }

    @Test
    void invalidDuration_400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/time-slots?date=" + day + "&durationMinutes=50")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dateBeyond90Days_400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/time-slots?date=" + LocalDate.now().plusDays(120) + "&durationMinutes=60")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RANGE"));
    }
}
