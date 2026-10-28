package pxt.dki.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingUpdateCancelTest extends AbstractIntegrationTest {

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    CalendarPreSyncPort calendarPreSync;

    private String alice;
    private String bob;
    private String admin;
    private UUID mars;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        bob = TestAuth.token(mockMvc, objectMapper, TestAuth.BOB, TestAuth.PASSWORD_USER);
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        mars = TestData.roomId(mockMvc, objectMapper, alice, "Mars");
        // use a far-future weekday per test class run to avoid clashes with other tests
        day = TestData.nextWeekday().plusDays(7);
    }

    private String create(int sh, int sm, int eh, int em) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Sprint plan","startTime":"%s","endTime":"%s"}"""
                                .formatted(mars, TestData.at(day, sh, sm), TestData.at(day, eh, em))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String updateJson(int sh, int sm, int eh, int em) {
        return """
                {"title":"Sprint plan v2","startTime":"%s","endTime":"%s"}"""
                .formatted(TestData.at(day, sh, sm), TestData.at(day, eh, em));
    }

    @Test
    void update_sameSlot_succeeds() throws Exception {
        String id = objectMapper.readTree(create(9, 0, 10, 0)).get("id").asText();
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(9, 0, 10, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sprint plan v2"));
    }

    @Test
    void update_intoOtherBooking_409() throws Exception {
        create(11, 0, 12, 0);
        String id = objectMapper.readTree(create(13, 0, 14, 0)).get("id").asText();
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(11, 30, 12, 30)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_CONFLICT"));
    }

    @Test
    void update_byOtherUser_403_byAdmin_200() throws Exception {
        String id = objectMapper.readTree(create(15, 0, 16, 0)).get("id").asText();
        org.mockito.Mockito.clearInvocations(calendarPreSync);
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(15, 0, 16, 0)))
                .andExpect(status().isForbidden());
        // a stranger is turned away before any Google import is triggered
        org.mockito.Mockito.verify(calendarPreSync, org.mockito.Mockito.never())
                .preSync(org.mockito.ArgumentMatchers.any());
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(15, 0, 16, 0)))
                .andExpect(status().isOk());
    }

    @Test
    void update_onDeactivatedRoom_422_ROOM_INACTIVE() throws Exception {
        String roomBody = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Temp-" + UUID.randomUUID() + "\",\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID roomId = UUID.fromString(objectMapper.readTree(roomBody).get("id").asText());

        String bookingBody = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"On temp","startTime":"%s","endTime":"%s"}"""
                                .formatted(roomId, TestData.at(day, 8, 0), TestData.at(day, 8, 30))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(bookingBody).get("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/rooms/" + roomId + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(8, 0, 8, 30)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ROOM_INACTIVE"));
    }

    @Test
    void create_malformedJson_400_notFiveHundred() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"not-a-uuid\",\"startTime\":\"2026-10-27T09:00:00\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void cancel_200_thenCancelAgain_422_thenSlotReusable() throws Exception {
        String id = objectMapper.readTree(create(17, 0, 18, 0)).get("id").asText();

        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/bookings/" + id + "/cancel")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_ACTIVE"));

        // the cancelled slot can be rebooked by someone else
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Retro","startTime":"%s","endTime":"%s"}"""
                                .formatted(mars, TestData.at(day, 17, 0), TestData.at(day, 18, 0))))
                .andExpect(status().isCreated());
    }
}
