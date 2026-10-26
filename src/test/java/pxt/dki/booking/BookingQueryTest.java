package pxt.dki.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingQueryTest extends AbstractIntegrationTest {

    private String alice;
    private String admin;
    private UUID venus;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        venus = TestData.roomId(mockMvc, objectMapper, alice, "Venus");
        day = TestData.nextWeekday().plusDays(14);
    }

    private void book(int sh, int eh) throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Q-%d","startTime":"%s","endTime":"%s"}"""
                                .formatted(venus, sh, TestData.at(day, sh, 0), TestData.at(day, eh, 0))))
                .andExpect(status().isCreated());
    }

    @Test
    void me_defaultsToUpcomingActive() throws Exception {
        book(9, 10);
        mockMvc.perform(get("/api/v1/bookings/me").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title").value(hasItem("Q-9")));
    }

    @Test
    void roomSchedule_returnsDayBookings_andRejectsLongRange() throws Exception {
        book(11, 12);
        mockMvc.perform(get("/api/v1/rooms/" + venus + "/schedule?from=" + day + "&to=" + day)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title").value(hasItem("Q-11")));

        mockMvc.perform(get("/api/v1/rooms/" + venus + "/schedule?from=" + day + "&to=" + day.plusDays(40))
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RANGE"));
    }

    @Test
    void daySchedule_includesRoomsWithoutBookings() throws Exception {
        mockMvc.perform(get("/api/v1/schedule?date=" + day)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[*].room.name").value(hasItem("Mercury")));
    }

    @Test
    void adminSearch_filterByRoom_paged_andUserForbidden() throws Exception {
        book(14, 15);
        mockMvc.perform(get("/api/v1/bookings?roomId=" + venus + "&page=0&size=10")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/v1/bookings").header("Authorization", "Bearer " + alice))
                .andExpect(status().isForbidden());
    }
}
