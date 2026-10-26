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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two bookings of the same room must be at least 15 minutes apart (cleaning/turnover break). */
class BookingGapRuleTest extends AbstractIntegrationTest {

    private String alice;
    private UUID room;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gap-" + UUID.randomUUID() + "\",\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        room = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        day = TestData.nextWeekday().plusDays(42);
    }

    private org.springframework.test.web.servlet.ResultActions book(int sh, int sm, int eh, int em) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                .header("Authorization", "Bearer " + alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"roomId":"%s","title":"Gap test","startTime":"%s","endTime":"%s"}"""
                        .formatted(room, TestData.at(day, sh, sm), TestData.at(day, eh, em))));
    }

    @Test
    void backToBack_rejected_409() throws Exception {
        book(9, 0, 10, 0).andExpect(status().isCreated());
        // starts exactly when the previous one ends -> violates the 15-minute break
        book(10, 0, 11, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_CONFLICT"));
    }

    @Test
    void fifteenMinuteGap_accepted() throws Exception {
        book(13, 0, 14, 0).andExpect(status().isCreated());
        book(14, 15, 15, 0).andExpect(status().isCreated());
        // ends exactly 15 minutes before the first one starts -> allowed
        book(11, 45, 12, 45).andExpect(status().isCreated());
    }

    @Test
    void gapExactlyFifteenMinutes_beforeExisting_accepted() throws Exception {
        book(16, 0, 17, 0).andExpect(status().isCreated());
        book(15, 0, 15, 45).andExpect(status().isCreated());
    }
}
