package pxt.dki.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingCreateTest extends AbstractIntegrationTest {

    private String alice;
    private String bob;
    private String admin;
    private UUID earth;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        bob = TestAuth.token(mockMvc, objectMapper, TestAuth.BOB, TestAuth.PASSWORD_USER);
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        earth = TestData.roomId(mockMvc, objectMapper, alice, "Earth");
        day = TestData.nextWeekday();
    }

    private String bookingJson(UUID roomId, int startHour, int startMin, int endHour, int endMin) {
        return """
                {"roomId":"%s","title":"Team sync","attendeeCount":5,
                 "startTime":"%s","endTime":"%s"}"""
                .formatted(roomId, TestData.at(day, startHour, startMin), TestData.at(day, endHour, endMin));
    }

    @Test
    void create_201_returnsBookingWithPlus7Offset() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(earth, 9, 0, 10, 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomName").value("Earth"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startTime").value(endsWith("+07:00")));
    }

    @Test
    void create_overlapping_409_withConflictInfo() throws Exception {
        String first = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(earth, 13, 0, 14, 0)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("id").asText();

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(earth, 13, 30, 14, 30)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_CONFLICT"))
                .andExpect(jsonPath("$.conflict.bookingId").value(firstId));
    }

    @Test
    void create_adjacentBookings_bothSucceed() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(earth, 15, 0, 16, 0)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(earth, 16, 0, 17, 0)))
                .andExpect(status().isCreated());
    }

    @Test
    void create_inactiveRoom_422_ROOM_INACTIVE() throws Exception {
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Closed-" + UUID.randomUUID() + "\",\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID roomId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/rooms/" + roomId + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(roomId, 9, 0, 10, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ROOM_INACTIVE"));
    }

    @Test
    void create_unknownRoom_404() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(UUID.randomUUID(), 9, 0, 10, 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_invalidBody_400_withErrors() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"%s\",\"title\":\"\"}".formatted(earth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void get_otherUsersBooking_403_andAdmin_200() throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(earth, 11, 0, 12, 0)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(get("/api/v1/bookings/" + id).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/bookings/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }
}
