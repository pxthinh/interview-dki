package pxt.dki.booking;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.TestData;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /bookings/me role filter: participants see bookings they are invited to; default shows both. */
class MyBookingsRoleTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String alice;
    private String bob;
    private String carol;
    private UUID room;
    private String bookingId;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        bob = TestAuth.token(mockMvc, objectMapper, TestAuth.BOB, TestAuth.PASSWORD_USER);
        carol = TestAuth.token(mockMvc, objectMapper, "carol@dki.local", TestAuth.PASSWORD_USER);
        room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, created_at, updated_at)
                VALUES (?, ?, 'F5', 10, true, now(), now())""", room, "Role-" + UUID.randomUUID());

        UUID bobId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.BOB);
        LocalDate day = TestData.nextWeekday();
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":"%s","title":"Role test","startTime":"%s","endTime":"%s",
                                 "participantIds":["%s"]}"""
                                .formatted(room, TestData.at(day, 9, 0), TestData.at(day, 10, 0), bobId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        bookingId = objectMapper.readTree(body).get("id").asText();
    }

    private java.util.List<String> myIds(String token, String roleParam) throws Exception {
        String url = "/api/v1/bookings/me" + (roleParam == null ? "" : "?role=" + roleParam);
        String body = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var ids = new java.util.ArrayList<String>();
        for (JsonNode n : objectMapper.readTree(body)) {
            ids.add(n.get("id").asText());
        }
        return ids;
    }

    @Test
    void participantSeesInvitedBooking_byRoleAndByDefault() throws Exception {
        assertThat(myIds(bob, "participant")).contains(bookingId);
        assertThat(myIds(bob, null)).contains(bookingId);
        assertThat(myIds(bob, "organizer")).doesNotContain(bookingId);
    }

    @Test
    void organizerFilterWorks_participantFilterExcludesOwn() throws Exception {
        assertThat(myIds(alice, "organizer")).contains(bookingId);
        assertThat(myIds(alice, "participant")).doesNotContain(bookingId);
        assertThat(myIds(alice, null)).contains(bookingId);
    }

    @Test
    void uninvitedUser_seesNothing() throws Exception {
        assertThat(myIds(carol, null)).doesNotContain(bookingId);
        assertThat(myIds(carol, "participant")).doesNotContain(bookingId);
    }
}
