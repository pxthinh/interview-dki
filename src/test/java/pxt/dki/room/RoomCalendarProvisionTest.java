package pxt.dki.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.calendar.FakeGoogleCalendarClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.calendar.enabled=true")
@Import(RoomCalendarProvisionTest.FakeClientConfig.class)
class RoomCalendarProvisionTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeGoogleCalendarClient fakeGoogleCalendarClient() {
            return new FakeGoogleCalendarClient();
        }
    }

    @Autowired
    FakeGoogleCalendarClient fake;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
    }

    @Test
    void createRoom_withoutCalendarId_autoCreatesGoogleCalendar() throws Exception {
        String name = "Auto-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.googleCalendarId").value("cal-" + name.toLowerCase()));
        assertThat(fake.calls).containsExactly("create-calendar:" + name);
    }

    @Test
    void createRoom_withExplicitCalendarId_keepsIt() throws Exception {
        String name = "Manual-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":5,\"googleCalendarId\":\"existing@group.calendar.google.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.googleCalendarId").value("existing@group.calendar.google.com"));
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void createRoom_googleDown_roomStillCreated_calendarNull() throws Exception {
        fake.alwaysFail.set(true);
        String name = "Down-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.googleCalendarId").isEmpty());
    }

    @Test
    void seededRooms_present_earthHasRealCalendar() throws Exception {
        String alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/rooms")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='Saigon')].capacity").value(14))
                .andExpect(jsonPath("$[?(@.name=='Earth')]").exists());
    }

    @Test
    void updateRoom_withoutCalendarId_keepsExistingId_noGoogleCall() throws Exception {
        String name = "Keep-" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":5,\"googleCalendarId\":\"existing@group.calendar.google.com\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(put("/api/v1/rooms/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleCalendarId").value("existing@group.calendar.google.com"));
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void updateRoom_stillWithoutCalendar_provisionsOne() throws Exception {
        fake.alwaysFail.set(true);
        String name = "Late-" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.googleCalendarId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        fake.reset();
        mockMvc.perform(put("/api/v1/rooms/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"capacity\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleCalendarId").value("cal-" + name.toLowerCase()));
        assertThat(fake.calls).containsExactly("create-calendar:" + name);
    }
}
