package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.gateway.GatewayCompleteRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * OLLAMA mode end to end through the real application path, with a
 * deterministic in-process JDK {@link HttpServer} standing in for Ollama:
 * no Ollama installation, no external network, and no live provider call.
 *
 * <p>Every completion goes through the real {@code GatewayController},
 * {@code GatewayCompletionService}, {@link LlmProviderSelector} and the
 * configuration-wired {@link OllamaLlmProvider} — the provider is never
 * instantiated by this test. The stub captures the exact HTTP request so the
 * wiring is proven end to end, and request-inspection BLOCK plus
 * provider-response-inspection BLOCK are both proven to short-circuit before
 * anything sensitive reaches the client.
 */
@SpringBootTest(properties = "aegivault.gateway.provider=OLLAMA")
@AutoConfigureMockMvc
class OllamaCompletionWiringTest {

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    private static final String PII = "alice@example.com";

    // Synthetic fixture assembled at runtime so no complete credential-like
    // literal appears in this source file.
    private static final String SYNTHETIC_KEY = "s" + "k-" + "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String STUB_RESPONSE = "{\"response\":\"ollama stub completion\"}";

    private static final HttpServer STUB = startStub();

    private static final List<RecordedRequest> REQUESTS = new ArrayList<>();

    private static volatile String responseBody = STUB_RESPONSE;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmProviderSelector selector;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    private record RecordedRequest(String method, String path, String body) {}

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(OllamaLlmProvider.GENERATE_PATH, OllamaCompletionWiringTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to start the Ollama stub server.", ex);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (REQUESTS) {
            REQUESTS.add(new RecordedRequest(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        }
        byte[] outgoing = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, outgoing.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(outgoing);
        }
    }

    @DynamicPropertySource
    static void ollamaBaseUrlPointsAtTheStub(DynamicPropertyRegistry registry) {
        registry.add(
                "aegivault.gateway.ollama.base-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetStub() {
        synchronized (REQUESTS) {
            REQUESTS.clear();
        }
        responseBody = STUB_RESPONSE;
    }

    private List<RecordedRequest> recordedRequests() {
        synchronized (REQUESTS) {
            return List.copyOf(REQUESTS);
        }
    }

    private String register() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("ollama-wiring-" + UUID.randomUUID() + "@example.com", "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String complete(String token, String model, String content) throws Exception {
        return mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GatewayCompleteRequest(model, content))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Set<String> strings(String json, String field) {
        Set<String> values = new HashSet<>();
        objectMapper.readTree(json).get(field).forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    void ollamaModeWiresExactlyOneProviderAndTheSelectorReturnsIt() {
        assertThat(context.getBeanNamesForType(LlmProvider.class)).hasSize(1);
        LlmProvider active = context.getBean(LlmProvider.class);
        assertThat(active)
                .isInstanceOf(OllamaLlmProvider.class)
                .isNotInstanceOf(MockLlmProvider.class);
        assertThat(selector.select("any-model-name")).isSameAs(active);
    }

    @Test
    void cleanRequestReachesTheOllamaStubThroughTheRealGatewayPath() throws Exception {
        String token = register();
        long ledgerBefore = ledger.count();

        String body = complete(token, "stub-model", CLEAN);

        var response = objectMapper.readTree(body);
        assertThat(response.get("verdict").asText()).isEqualTo("ALLOW");
        assertThat(response.get("provider").get("model").asText()).isEqualTo("stub-model");
        assertThat(response.get("provider").get("content").asText())
                .isEqualTo("ollama stub completion");

        List<RecordedRequest> requests = recordedRequests();
        assertThat(requests).hasSize(1);
        RecordedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(OllamaLlmProvider.GENERATE_PATH);
        var sent = objectMapper.readTree(request.body());
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("model", "prompt", "stream");
        assertThat(sent.get("model").asText()).isEqualTo("stub-model");
        assertThat(sent.get("prompt").asText()).isEqualTo(CLEAN);
        assertThat(sent.get("stream").asBoolean()).isFalse();

        // Request inspection is audited before the provider call.
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
    }

    @Test
    void requestInspectionBlockNeverReachesTheOllamaStub() throws Exception {
        String token = register();

        String body = complete(token, "stub-model", "contact " + PII + " for access.");

        assertThat(objectMapper.readTree(body).get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(objectMapper.readTree(body).get("provider")).isNull();
        assertThat(strings(body, "reasons")).contains("PII_DETECTED");
        assertThat(strings(body, "detectedPiiTypes")).contains("EMAIL");
        assertThat(body).doesNotContain(PII);
        assertThat(recordedRequests())
                .as("a request blocked by inspection must never call Ollama")
                .isEmpty();
    }

    @Test
    void providerResponseInspectionBlocksSensitiveCompletionBeforeTheClient() throws Exception {
        responseBody = "{\"response\":\"contact " + PII + " with key " + SYNTHETIC_KEY + ".\"}";
        String token = register();

        String body = complete(token, "stub-model", CLEAN);

        assertThat(objectMapper.readTree(body).get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(objectMapper.readTree(body).get("provider"))
                .as("sensitive provider content must never be returned")
                .isNull();
        assertThat(strings(body, "reasons"))
                .containsExactlyInAnyOrder("PII_DETECTED", "SECRET_DETECTED");
        assertThat(body).doesNotContain(PII, SYNTHETIC_KEY);

        // The provider was genuinely called, then its response was inspected
        // and blocked: proof the response-inspection stage is in the path.
        assertThat(recordedRequests()).hasSize(1);
    }
}
