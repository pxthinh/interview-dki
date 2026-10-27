package pxt.dki.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Amenity catalog: admin CRUD; rooms keep their stored strings even when an amenity is removed. */
class AmenityTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private String alice;

    @BeforeEach
    void setUp() throws Exception {
        admin = TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
        alice = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
    }

    private List<String> list() throws Exception {
        String body = mockMvc.perform(get("/api/v1/amenities").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readerForListOf(String.class).readValue(body);
    }

    @Test
    void seededFromExistingRooms() throws Exception {
        // seed rooms (V3) carry amenities like "TV"/"Bảng trắng"; the migration copies them in
        assertThat(list()).isNotEmpty();
    }

    @Test
    void adminAddsAndDeletes_duplicate422_unknown404() throws Exception {
        String name = "May chieu 4K " + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/admin/amenities")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
        assertThat(list()).contains(name);

        mockMvc.perform(post("/api/v1/admin/amenities")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("AMENITY_EXISTS"));

        mockMvc.perform(delete("/api/v1/admin/amenities").param("name", name)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        assertThat(list()).doesNotContain(name);

        mockMvc.perform(delete("/api/v1/admin/amenities").param("name", "khong-ton-tai")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void vietnameseNameWithSpaces_fullLifecycle() throws Exception {
        String name = "Màn hình LED " + UUID.randomUUID().toString().substring(0, 6);
        mockMvc.perform(post("/api/v1/admin/amenities")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("name", name))))
                .andExpect(status().isCreated());
        assertThat(list()).contains(name);
        mockMvc.perform(delete("/api/v1/admin/amenities").param("name", name)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        assertThat(list()).doesNotContain(name);
    }

    @Test
    void nameWithPathHostileCharacters_rejected() throws Exception {
        for (String bad : new String[]{"Loa/Mic", "HDMI\\VGA", "A%B", "x;y", ".", ".."}) {
            mockMvc.perform(post("/api/v1/admin/amenities")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", bad))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("AMENITY_INVALID_NAME"));
        }
    }

    @Test
    void nonAdmin_403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/amenities")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/amenities").param("name", "X")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingUsedAmenity_leavesRoomUntouched() throws Exception {
        String name = "Loa-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/admin/amenities")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());

        UUID room = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, location, capacity, active, amenities, created_at, updated_at)
                VALUES (?, ?, 'F4', 6, true, ?, now(), now())""",
                room, "Amen-" + UUID.randomUUID(), new String[]{name});

        mockMvc.perform(delete("/api/v1/admin/amenities").param("name", name)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        String stored = jdbc.queryForObject(
                "SELECT amenities[1] FROM rooms WHERE id = ?", String.class, room);
        assertThat(stored).isEqualTo(name);
    }
}
