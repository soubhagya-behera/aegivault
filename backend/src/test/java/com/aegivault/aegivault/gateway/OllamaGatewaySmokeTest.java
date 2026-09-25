package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.audit.AuditLedgerVerificationService;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.OllamaLlmProvider;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
/**
 * Opt-in end-to-end smoke test for the live Ollama gateway path.
 *
 * <p>Runs only when {@code AEGIVAULT_OLLAMA_TEST=true} (environment) or
 * {@code -Daegivault.ollama.test=true} is set; default suite skips this
 * class with no Ollama connection, so the normal build never requires
 * Ollama. When enabled, exercises the real Spring path
 * {@code POST /api/gateway/complete} (JWT auth, request inspection,
 * audit, configured OllamaLlmProvider, response inspection, HTTP
 * response) against a local Ollama server. A local model must already
 * be installed (default llama3.2, override with AEGIVAULT_OLLAMA_MODEL
 * or -Daegivault.ollama.model); base URL defaults to the configured
 * Ollama settings and may be overridden with AEGIVAULT_OLLAMA_BASE_URL
 * or -Daegivault.ollama.base-url.
 */
@EnabledIfOllamaSmokeTest
@SpringBootTest(properties = "aegivault.gateway.provider=OLLAMA")
@AutoConfigureMockMvc
class OllamaGatewaySmokeTest {

    private static final String DEFAULT_MODEL = "llama3.2";

    private static final String PROMPT = "Reply with exactly: AEGIVAULT_OLLAMA_SMOKE_OK";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    @Autowired
    private AuditLedgerVerificationService verification;

    @Autowired
    private LlmProvider provider;

    @DynamicPropertySource
    static void ollamaBaseUrlOverride(DynamicPropertyRegistry registry) {
        String override = firstNonBlank(System.getenv("AEGIVAULT_OLLAMA_BASE_URL"),
                System.getProperty("aegivault.ollama.base-url"));
        if (override != null) {
            registry.add("aegivault.gateway.ollama.base-url", () -> override);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    static String smokeModel() {
        String override = firstNonBlank(System.getenv("AEGIVAULT_OLLAMA_MODEL"),
                System.getProperty("aegivault.ollama.model"));
        return override != null ? override : DEFAULT_MODEL;
    }

    private static String email() {
        return "ollama-smoke-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private Set<UUID> entryIds() {
        return ledger.findAll().stream().map(AuditLedgerEntry::getId).collect(Collectors.toSet());
    }

    @Test
    void liveOllamaCompletionThroughTheRealGatewayPath() throws Exception {
        assertThat(provider)
                .as("smoke test requires aegivault.gateway.provider=OLLAMA wiring")
                .isInstanceOf(OllamaLlmProvider.class);

        String model = smokeModel();
        String token = register(email());
        Set<UUID> before = entryIds();

        final MvcResult result;
        try {
            result = mvc.perform(post("/api/gateway/complete")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"" + model + "\",\"content\":\"" + PROMPT + "\"}"))
                    .andReturn();
        } catch (Exception ex) {
            fail("Live Ollama smoke test could not reach Ollama for model '" + model
                    + "'. Start local Ollama with that model installed or check base URL override.", ex);
            return;
        }

        int httpStatus = result.getResponse().getStatus();
        if (httpStatus != 200) {
            fail("Live Ollama smoke test could not reach Ollama for model '" + model
                    + "': gateway returned HTTP " + httpStatus
                    + ". Start local Ollama with that model installed"
                    + " (documented default '" + DEFAULT_MODEL + "')"
                    + " or check AEGIVAULT_OLLAMA_BASE_URL / -Daegivault.ollama.base-url.");
        }

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("verdict").asText()).isEqualTo("ALLOW");
        assertThat(body.get("provider").get("model").asText()).isEqualTo(model);
        assertThat(body.get("provider").get("content").asText()).isNotBlank();
        assertThat(body.has("actorSubject")).isFalse();
        assertThat(body.has("requestId")).isFalse();
        assertThat(body.has("content")).isFalse();

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("entryHash", "previousHash", "sequenceNumber", "eventData");

        JsonNode providerNode = objectMapper.readTree(response).get("provider");
        assertThat(providerNode).isNotNull();
        assertThat(providerNode.get("content").asText()).isNotBlank();

        var created = ledger.findAll().stream().filter(entry -> !before.contains(entry.getId())).toList();
        assertThat(created).hasSize(1);
        AuditLedgerEntry entry = created.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventData.GATEWAY_INSPECTION_ALLOWED);
        assertThat(entry.getResourceType()).isEqualTo(AuditEventData.AI_GATEWAY_INSPECTION_RESOURCE);
        JsonNode eventData = objectMapper.readTree(entry.getEventData());
        assertThat(eventData.propertyNames())
                .containsExactlyInAnyOrder("model", "verdict", "reasons", "detectedPiiTypes");
        assertThat(entry.getEventData()).doesNotContain(PROMPT, token, "actorSubject");

        assertThat(verification.verify().valid())
                .as("audit verification must stay valid after the live Ollama completion")
                .isTrue();
    }
}

