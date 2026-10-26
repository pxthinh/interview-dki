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
 * GET /rooms/time-slots: pickable start times (15-minute grid) for a date, and — once a start
 * is chosen — pickable end times. Times where every matching room is taken (including the
 * 15-minute break) are not offered.
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

    private JsonNode fetch(String start) throws Exception {
        var req = get("/api/v1/rooms/time-slots")
                .param("date", day.toString())
                .param("amenities", amenity)
                .header("Authorization", "Bearer " + alice);
        if (start != null) {
            req = req.param("start", start);
        }
        String body = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private List<String> times(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            assertThat(n.get("availableRooms").asInt()).isGreaterThan(0);
            out.add(n.get("time").asText().substring(11, 16));
        }
        return out;
    }

    private void book(int sh, int sm, int eh, int em) throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Busy","startTime":"%s","endTime":"%s"}"""
                                .formatted(room, TestData.at(day, sh, sm), TestData.at(day, eh, em))))
                .andExpect(status().isCreated());
    }

    @Test
    void emptyDay_startsCoverWorkingHours_endTimesEmptyWithoutStart() throws Exception {
        JsonNode res = fetch(null);
        List<String> starts = times(res.get("startTimes"));
        assertThat(starts).first().isEqualTo("07:00");
        assertThat(starts).last().isEqualTo("19:45"); // last start allowing a 15m meeting before 20:00
        assertThat(starts).hasSize(52);
        assertThat(res.get("endTimes")).isEmpty();
    }

    @Test
    void withStart_endTimesRunUntilClosing_cappedAt8h() throws Exception {
        JsonNode res = fetch(TestData.at(day, 8, 0).toString());
        List<String> ends = times(res.get("endTimes"));
        assertThat(ends).first().isEqualTo("08:15");
        assertThat(ends).last().isEqualTo("16:00"); // 8h cap before the 20:00 close
        assertThat(ends).hasSize(32);
    }

    @Test
    void bookedWindow_hiddenFromStarts_andCapsEndTimes() throws Exception {
        book(9, 0, 9, 45);

        JsonNode res = fetch(null);
        List<String> starts = times(res.get("startTimes"));
        // busy 09:00-09:45 + 15m break blocks starts 08:45..09:45 (a start needs >=15m of room)
        assertThat(starts).contains("08:30", "10:00");
        assertThat(starts).doesNotContain("08:45", "09:00", "09:30", "09:45");

        // from 07:00 the meeting can run until 08:45 at the latest (then the break hits 09:00)
        List<String> ends = times(fetch(TestData.at(day, 7, 0).toString()).get("endTimes"));
        assertThat(ends).last().isEqualTo("08:45");
        assertThat(ends).doesNotContain("09:00", "09:15");
    }

    @Test
    void roomScoped_excludeBooking_ownSlotStaysSelectable() throws Exception {
        book(9, 0, 10, 0);
        String bookingId = null;
        var mine = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/bookings/me")
                                .param("from", TestData.at(day, 0, 0).toString())
                                .header("Authorization", "Bearer " + alice))
                        .andReturn().getResponse().getContentAsString());
        for (var n : mine) {
            if (room.toString().equals(n.get("roomId").asText())) {
                bookingId = n.get("id").asText();
            }
        }
        assertThat(bookingId).isNotNull();

        // scoped to this room WITHOUT exclusion: 09:00 hidden
        var req1 = get("/api/v1/rooms/time-slots")
                .param("date", day.toString())
                .param("roomId", room.toString())
                .header("Authorization", "Bearer " + alice);
        var without = objectMapper.readTree(mockMvc.perform(req1).andReturn().getResponse().getContentAsString());
        assertThat(times(without.get("startTimes"))).doesNotContain("09:00");

        // WITH exclusion (editing that booking): its own 09:00 start is offered again
        var req2 = get("/api/v1/rooms/time-slots")
                .param("date", day.toString())
                .param("roomId", room.toString())
                .param("excludeBookingId", bookingId)
                .header("Authorization", "Bearer " + alice);
        var with = objectMapper.readTree(mockMvc.perform(req2).andReturn().getResponse().getContentAsString());
        assertThat(times(with.get("startTimes"))).contains("09:00");
    }

    @Test
    void startOffGrid_400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/time-slots")
                        .param("date", day.toString())
                        .param("start", TestData.at(day, 9, 0).plusMinutes(5).toString())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dateBeyond90Days_400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/time-slots")
                        .param("date", LocalDate.now().plusDays(120).toString())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RANGE"));
    }
}
