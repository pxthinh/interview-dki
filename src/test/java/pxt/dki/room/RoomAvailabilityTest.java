package pxt.dki.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoomAvailabilityTest extends AbstractIntegrationTest {

    private String alice;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        day = TestData.nextWeekday().plusDays(21);
    }

    @Test
    void availability_excludesBookedRoom_filters_andSorts() throws Exception {
        UUID mercury = TestData.roomId(mockMvc, objectMapper, alice, "Mercury");
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Busy","startTime":"%s","endTime":"%s"}"""
                                .formatted(mercury, TestData.at(day, 9, 0), TestData.at(day, 10, 0))))
                .andExpect(status().isCreated());

        // booked room excluded; result sorted by capacity ascending
        mockMvc.perform(get("/api/v1/rooms/availability")
                        .param("start", TestData.at(day, 9, 0).toString())
                        .param("end", TestData.at(day, 10, 0).toString())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Mercury"))))
                .andExpect(jsonPath("$[0].name").value("Venus"));

        // adjacent slot: Mercury free again
        mockMvc.perform(get("/api/v1/rooms/availability")
                        .param("start", TestData.at(day, 10, 0).toString())
                        .param("end", TestData.at(day, 11, 0).toString())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem("Mercury")));
    }

    @Test
    void availability_minCapacityAndAmenities_filter() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/availability")
                        .param("start", TestData.at(day, 13, 0).toString())
                        .param("end", TestData.at(day, 14, 0).toString())
                        .param("minCapacity", "10")
                        .param("amenities", "projector", "whiteboard")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem("Mars")))
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Jupiter"))));
    }

    @Test
    void availability_endBeforeStart_400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/availability")
                        .param("start", TestData.at(day, 14, 0).toString())
                        .param("end", TestData.at(day, 13, 0).toString())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RANGE"));
    }
}
