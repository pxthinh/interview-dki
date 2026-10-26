package pxt.dki.user;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest extends AbstractIntegrationTest {

    private String adminToken() throws Exception {
        return TestAuth.token(mockMvc, objectMapper, TestAuth.ADMIN, TestAuth.PASSWORD_ADMIN);
    }

    private String createUser(String adminToken, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","fullName":"New User","password":"%s","role":"USER"}"""
                                .formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void admin_createUser_201() throws Exception {
        createUser(adminToken(), "dave@dki.local", "Dave@1234");
    }

    @Test
    void user_createUser_403() throws Exception {
        String token = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"eve@dki.local","fullName":"Eve","password":"Eve@12345","role":"USER"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDuplicateEmail_422_EMAIL_TAKEN() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@dki.local","fullName":"Dup","password":"Dup@12345","role":"USER"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_TAKEN"));
    }

    @Test
    void admin_deactivateUser_thenLogin401() throws Exception {
        String admin = adminToken();
        String body = createUser(admin, "frank@dki.local", "Frank@123");
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(patch("/api/v1/users/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"frank@dki.local\",\"password\":\"Frank@123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_thenLoginWithNew_200() throws Exception {
        String admin = adminToken();
        createUser(admin, "grace@dki.local", "Grace@123");
        String token = TestAuth.token(mockMvc, objectMapper, "grace@dki.local", "Grace@123");

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Grace@123\",\"newPassword\":\"Grace@456\"}"))
                .andExpect(status().isNoContent());

        TestAuth.token(mockMvc, objectMapper, "grace@dki.local", "Grace@456");
    }

    @Test
    void admin_listUsers_paged() throws Exception {
        mockMvc.perform(get("/api/v1/users?page=0&size=2")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }
}
