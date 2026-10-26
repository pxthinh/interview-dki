package pxt.dki.room;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoomControllerTest extends AbstractIntegrationTest {

    @Test
    void user_listRooms_200_seededFive() throws Exception {
        String token = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(get("/api/v1/rooms?active=true").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[*].name").value(hasItem("Earth")));
    }

    @Test
    void user_createRoom_403() throws Exception {
        String token = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hack\",\"capacity\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_createRoom_201() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Saturn","location":"Tang 4","capacity":10,
                                 "amenities":["projector"],"googleCalendarId":null}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Saturn"))
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.amenities[0]").value("projector"));
    }

    @Test
    void admin_duplicateName_422() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Earth\",\"capacity\":8}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ROOM_NAME_TAKEN"));
    }

    @Test
    void admin_deactivate_thenListActiveExcludesIt() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pluto\",\"capacity\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(patch("/api/v1/rooms/" + id + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/rooms?active=true").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Pluto"))));
    }
}
