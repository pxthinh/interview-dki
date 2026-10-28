package pxt.dki.room;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoomControllerTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

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
                .andExpect(jsonPath("$.amenities[0]").value("projector"))
                .andExpect(jsonPath("$.adminUserId").isNotEmpty()); // creator becomes room admin
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

    @Test
    void admin_duplicateName_caseAndWhitespaceInsensitive_422() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        // "Mercury" is Flyway-seeded; the trimmed, case-folded name collides with it
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mercury \",\"capacity\":8}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ROOM_NAME_TAKEN"));
    }

    @Test
    void admin_createRoom_nameTrimmed_amenitiesTrimmedAndDeduped() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Neptune \",\"capacity\":6,\"amenities\":[\" tv \",\"tv\",\"whiteboard\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Neptune"))
                .andExpect(jsonPath("$.amenities.length()").value(2))
                .andExpect(jsonPath("$.amenities[0]").value("tv"))
                .andExpect(jsonPath("$.amenities[1]").value("whiteboard"));
    }

    @Test
    void admin_createRoom_amenityWithComma_422() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Comma\",\"capacity\":4,\"amenities\":[\"a,b\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("AMENITY_INVALID_NAME"));
    }

    @Test
    void admin_createRoom_tooManyAmenities_422() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        List<String> amenities = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            amenities.add("am" + i);
        }
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "Crowded", "capacity", 4, "amenities", amenities))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("AMENITY_LIST_TOO_LONG"));
    }

    @Test
    void admin_createRoom_adminUserIdWithUserRole_422() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        UUID alice = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, TestAuth.ALICE);
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Owned\",\"capacity\":4,\"adminUserId\":\"" + alice + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ROOM_ADMIN_INVALID"));
    }

    @Test
    void admin_update_withoutGoogleCalendarId_keepsPreviousId() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Uranus\",\"capacity\":4,\"googleCalendarId\":\"keep@group.calendar.google.com\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(put("/api/v1/rooms/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Uranus\",\"location\":\"Tang 6\",\"capacity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(5))
                .andExpect(jsonPath("$.googleCalendarId").value("keep@group.calendar.google.com"));
    }

    @Test
    void admin_update_renameOntoOtherRoomCaseInsensitive_422_selfOk() throws Exception {
        String admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Titan\",\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(put("/api/v1/rooms/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" EARTH\",\"capacity\":4}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ROOM_NAME_TAKEN"));
        // same room, different case: not a collision with itself
        mockMvc.perform(put("/api/v1/rooms/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TITAN \",\"capacity\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TITAN"));
    }

    @Test
    void seededRooms_haveAdminUserId() throws Exception {
        // Flyway's V6 backfill runs before users exist on a fresh DB; SeedDataRunner fills the gap.
        // Only the V3-seeded rooms are checked: other test classes insert admin-less rooms via JDBC.
        String token = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        String body = mockMvc.perform(get("/api/v1/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Set<String> seeded = Set.of("Mercury", "Venus", "Earth", "Mars", "Jupiter",
                "Mekong", "Danang", "Hanoi", "Saigon", "Halong");
        int checked = 0;
        for (JsonNode room : objectMapper.readTree(body)) {
            if (seeded.contains(room.get("name").asText())) {
                assertThat(room.get("adminUserId").isNull()).as("adminUserId of " + room.get("name")).isFalse();
                checked++;
            }
        }
        assertThat(checked).isEqualTo(seeded.size());
    }
}
