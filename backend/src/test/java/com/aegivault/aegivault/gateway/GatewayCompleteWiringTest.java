package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.MockLlmProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Completion endpoint through the real Spring wiring (no test doubles):
 * proves the production {@code LlmProviderSelector} resolves the
 * configuration-wired mock provider ({@code aegivault.gateway.provider=MOCK})
 * and serves ALLOW requests while BLOCK still returns the
 * safe decision. The provider is pinned to MOCK so the test never depends
 * on a developer's local provider configuration.
 */
@SpringBootTest(properties = "aegivault.gateway.provider=MOCK")
@AutoConfigureMockMvc
class GatewayCompleteWiringTest {

    private static final String EMAIL = "alice@example.com";

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmProviderSelector selector;

    @Autowired
    private LlmProvider provider;

    private String register() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("gateway-wiring-" + UUID.randomUUID() + "@example.com", "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void selectorResolvesTheMockProvider() {
        assertThat(selector.select("local-test-model"))
                .isSameAs(provider)
                .isInstanceOf(MockLlmProvider.class);
    }

    @Test
    void allowReachesTheConfiguredProvider() throws Exception {
        String token = register();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"))
                .andExpect(jsonPath("$.provider.model").value("local-test-model"))
                .andReturn();

        String providerContent =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("provider").get("content").asText();
        assertThat(providerContent).contains("MOCK", "not an AI answer", "local-test-model");
    }

    @Test
    void blockReturnsTheSafeDecision() throws Exception {
        String token = register();

        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"contact " + EMAIL + " for access.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.provider").doesNotExist());
    }
}
