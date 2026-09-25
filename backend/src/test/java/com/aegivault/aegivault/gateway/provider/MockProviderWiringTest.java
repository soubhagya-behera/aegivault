package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
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
 * MOCK mode through the real application context: the default configuration
 * wires exactly one {@link LlmProvider} bean — the deterministic
 * {@link MockLlmProvider} — and the selector returns it. A full completion
 * round trip is then run while the Ollama configuration points at a counting
 * JDK stub server on a real local port: zero requests reach it, proving MOCK
 * mode never touches Ollama and needs no Ollama installation.
 */
@SpringBootTest(properties = "aegivault.gateway.provider=MOCK")
@AutoConfigureMockMvc
class MockProviderWiringTest {

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    private static final AtomicInteger OLLAMA_REQUESTS = new AtomicInteger();

    private static final HttpServer OLLAMA_STUB = startStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmProviderSelector selector;

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", MockProviderWiringTest::recordStubCall);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to start the Ollama stub server.", ex);
        }
    }

    private static void recordStubCall(HttpExchange exchange) throws IOException {
        OLLAMA_REQUESTS.incrementAndGet();
        byte[] body = "{\"response\":\"unexpected ollama stub call\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Points the Ollama configuration at the stub server for this context: if
     * provider selection ever fell through to Ollama, the stub would record
     * the call and the completion would no longer be the mock's.
     */
    @DynamicPropertySource
    static void ollamaBaseUrlPointsAtTheStub(DynamicPropertyRegistry registry) {
        registry.add(
                "aegivault.gateway.ollama.base-url",
                () -> "http://127.0.0.1:" + OLLAMA_STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        OLLAMA_STUB.stop(0);
    }

    private String register() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("mock-wiring-" + UUID.randomUUID() + "@example.com", "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void defaultConfigurationWiresExactlyOneProviderAndItIsTheMock() {
        assertThat(context.getBeanNamesForType(LlmProvider.class)).hasSize(1);
        assertThat(context.getBean(LlmProvider.class))
                .isInstanceOf(MockLlmProvider.class)
                .isNotInstanceOf(OllamaLlmProvider.class);
    }

    @Test
    void selectorReturnsTheActiveMockProvider() {
        assertThat(selector.select("local-test-model"))
                .isSameAs(context.getBean(LlmProvider.class))
                .isInstanceOf(MockLlmProvider.class);
    }

    @Test
    void completionStillUsesTheMockAndMakesNoOllamaRequest() throws Exception {
        String token = register();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"))
                .andExpect(jsonPath("$.provider.model").value("local-test-model"))
                .andReturn();

        String providerContent = objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("provider")
                .get("content")
                .asText();
        assertThat(providerContent).contains("MOCK", "not an AI answer", "local-test-model");
        assertThat(OLLAMA_REQUESTS.get())
                .as("MOCK mode must never make an Ollama HTTP request")
                .isZero();
    }
}
