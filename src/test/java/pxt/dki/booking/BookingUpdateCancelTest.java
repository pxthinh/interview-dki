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
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(15, 0, 16, 0)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/bookings/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(15, 0, 16, 0)))
                .andExpect(status().isOk());
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
