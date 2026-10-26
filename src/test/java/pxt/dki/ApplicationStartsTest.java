package pxt.dki;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationStartsTest extends AbstractIntegrationTest {

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
