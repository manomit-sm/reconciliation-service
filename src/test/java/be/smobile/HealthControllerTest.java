package be.smobile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();
    }

    @Test
    void health_returnsOkWithHealthyMessage() {
        HealthController controller = new HealthController();

        assertThat(controller.health()).isEqualTo("I am healthy");
    }

    @Test
    void healthEndpoint_respondsAtConfiguredPath() throws Exception {
        mockMvc.perform(get("/health/status"))
                .andExpect(status().isOk())
                .andExpect(content().string("I am healthy"));
    }
}
