package pxt.dki.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;
import pxt.dki.room.RoomRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default profile: calendar sync off. Bookings work; no outbox rows are written. */
class CalendarSyncDisabledTest extends AbstractIntegrationTest {

    @Autowired
    CalendarOutboxRepository outboxRepository;

    @Autowired
    RoomRepository roomRepository;

    @Test
    void disabled_bookingSucceeds_noOutboxRows() throws Exception {
        String alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        var mercury = roomRepository.findByNameIgnoreCase("Mercury").orElseThrow();
        mercury.setGoogleCalendarId("cal-mercury");
        roomRepository.save(mercury);

        var day = TestData.nextWeekday().plusDays(35);
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Offline","startTime":"%s","endTime":"%s"}"""
                                .formatted(mercury.getId(), TestData.at(day, 9, 0), TestData.at(day, 10, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var bookingId = java.util.UUID.fromString(objectMapper.readTree(body).get("id").asText());

        assertThat(outboxRepository.findByBookingIdOrderById(bookingId)).isEmpty();
    }
}
