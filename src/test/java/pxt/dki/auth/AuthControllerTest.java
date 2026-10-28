package pxt.dki.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestAuth;
import pxt.dki.user.Role;
import pxt.dki.user.User;
import pxt.dki.user.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void login_ok_returnsTokenAndUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@dki.local\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").value(28800))
                .andExpect(jsonPath("$.user.email").value("admin@dki.local"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void login_wrongPassword_401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@dki.local\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("BAD_CREDENTIALS"));
    }

    @Test
    void me_withoutToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_withToken_200() throws Exception {
        String token = TestAuth.token(mockMvc, objectMapper, TestAuth.ALICE, TestAuth.PASSWORD_USER);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(TestAuth.ALICE));
    }

    @Test
    void deactivatedUser_tokenRejected() throws Exception {
        User u = new User();
        u.setEmail("temp@dki.local");
        u.setPasswordHash(passwordEncoder.encode("Temp@123"));
        u.setFullName("Temp");
        u.setRole(Role.USER);
        u.setActive(true);
        userRepository.save(u);

        String token = TestAuth.token(mockMvc, objectMapper, "temp@dki.local", "Temp@123");

        u.setActive(false);
        userRepository.save(u);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChange_rejectsTokensIssuedBefore() throws Exception {
        User u = new User();
        u.setEmail("rotate@dki.local");
        u.setPasswordHash(passwordEncoder.encode("Rotate@123"));
        u.setFullName("Rotate");
        u.setRole(Role.USER);
        u.setActive(true);
        userRepository.save(u);

        String oldToken = TestAuth.token(mockMvc, objectMapper, "rotate@dki.local", "Rotate@123");
        // JWT iat has second precision: make sure the change lands in a later second than the issue
        Thread.sleep(1100);

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Rotate@123\",\"newPassword\":\"Rotate@456\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        String newToken = TestAuth.token(mockMvc, objectMapper, "rotate@dki.local", "Rotate@456");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    void login_unknownEmail_401_sameAsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@dki.local\",\"password\":\"whatever1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("BAD_CREDENTIALS"));
    }
}
