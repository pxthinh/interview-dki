package pxt.dki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Logs in through the real endpoint and returns bearer tokens for tests. */
public final class TestAuth {

    public static final String ADMIN = "admin@dki.local";
    public static final String ALICE = "alice@dki.local";
    public static final String BOB = "bob@dki.local";
    public static final String PASSWORD_ADMIN = "Admin@123";
    public static final String PASSWORD_USER = "User@123";

    private TestAuth() {
    }

    public static String token(MockMvc mockMvc, ObjectMapper objectMapper, String email, String password)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("accessToken").asText();
    }
}
