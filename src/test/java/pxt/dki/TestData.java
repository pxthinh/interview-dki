package pxt.dki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;
import pxt.dki.common.time.BusinessTime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class TestData {

    private TestData() {
    }

    /** A wall-clock time in Vietnam on the given date. */
    public static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(BusinessTime.ZONE).toOffsetDateTime();
    }

    /** Tomorrow or later, skipping weekends to keep test dates unremarkable. */
    public static LocalDate nextWeekday() {
        LocalDate d = LocalDate.now(BusinessTime.ZONE).plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    public static UUID roomId(MockMvc mockMvc, ObjectMapper objectMapper, String token, String roomName)
            throws Exception {
        String body = mockMvc.perform(get("/api/v1/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(body)) {
            if (roomName.equals(node.get("name").asText())) {
                return UUID.fromString(node.get("id").asText());
            }
        }
        throw new IllegalStateException("Room not found: " + roomName);
    }
}
