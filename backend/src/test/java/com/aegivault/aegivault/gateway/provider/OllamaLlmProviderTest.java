package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@link OllamaLlmProvider} against a deterministic in-process
 * HTTP server (JDK {@link HttpServer}, no real Ollama installation, no
 * new test dependencies): request mapping, response mapping, and safe
 * generic failures that never leak host/port internals, raw Ollama
 * bodies, or request content.
 */
class OllamaLlmProviderTest {

    private static final String GENERIC_MESSAGE = "Unable to complete Ollama request.";

    private static final String ERROR_MARKER = "ollama-boom-marker-8f3a91";

    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;

    private ExecutorService executor;

    private volatile int status = 200;

    private volatile String responseBody =
            "{\"model\":\"test-model\",\"response\":\"generated text here.\",\"done\":true}";

    private volatile long delayMillis = 0;

    private final AtomicReference<String> method = new AtomicReference<>();

    private final AtomicReference<String> path = new AtomicReference<>();

    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(OllamaLlmProvider.GENERATE_PATH, this::handle);
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
        status = 200;
        delayMillis = 0;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] outgoing = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, outgoing.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(outgoing);
            }
        } catch (IOException ex) {
            // Client already gone (e.g. timeout test) — nothing to assert here.
        }
    }

    private OllamaProperties properties() {
        OllamaProperties properties = new OllamaProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        return properties;
    }

    @Test
    void postsModelAndPromptToGeneratePath() throws Exception {
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "summarize this."));

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/api/generate");
        assertThat(json.readTree(requestBody.get()).path("model").asText()).isEqualTo("test-model");
        assertThat(json.readTree(requestBody.get()).path("prompt").asText()).isEqualTo("summarize this.");
        assertThat(response).isEqualTo(new LlmResponse("test-model", "generated text here."));
    }

    @Test
    void requestBodyContainsOnlyModelPromptAndStream() throws Exception {
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        provider.complete(new LlmRequest("test-model", "summarize this."));

        Set<String> fields = json.readTree(requestBody.get()).properties().stream()
                .map(entry -> entry.getKey())
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder("model", "prompt", "stream");
        assertThat(json.readTree(requestBody.get()).path("stream").asBoolean()).isFalse();
    }

    @Test
    void responseMapsRequestedModelAndGeneratedText() {
        responseBody = "{\"model\":\"other-model\",\"created_at\":\"2026-01-01\",\"response\":\"hello.\",\"done\":true}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("requested-model", "say hello."));

        assertThat(response.model()).isEqualTo("requested-model");
        assertThat(response.content()).isEqualTo("hello.");
    }

    @Test
    void responseWithUsageFieldsMapsCountsAndLeavesTotalUnknown() {
        responseBody =
                "{\"model\":\"test-model\",\"response\":\"hello.\",\"done\":true,\"prompt_eval_count\":12,\"eval_count\":34}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "say hello."));

        assertThat(response.content()).isEqualTo("hello.");
        assertThat(response.usage()).isEqualTo(new LlmUsage(12L, 34L, null));
        assertThat(response.usage().isUnknown()).isFalse();
    }

    @Test
    void responseWithoutUsageFieldsRemainsUnknown() {
        responseBody = "{\"model\":\"test-model\",\"response\":\"hello.\",\"done\":true}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "say hello."));

        assertThat(response.content()).isEqualTo("hello.");
        assertThat(response.usage()).isEqualTo(LlmUsage.unknown());
        assertThat(response.usage().isUnknown()).isTrue();
    }

    @Test
    void partialUsageFieldsMapOnlyWhatIsPresent() {
        responseBody = "{\"model\":\"test-model\",\"response\":\"hello.\",\"done\":true,\"eval_count\":7}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "say hello."));

        assertThat(response.usage()).isEqualTo(new LlmUsage(null, 7L, null));
    }

    @Test
    void nonNumericUsageValuesStayUnknownWithoutFailing() {
        responseBody =
                "{\"model\":\"test-model\",\"response\":\"hello.\",\"done\":true,"
                        + "\"prompt_eval_count\":\"twelve\",\"eval_count\":true}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "say hello."));

        assertThat(response.content()).isEqualTo("hello.");
        assertThat(response.usage()).isEqualTo(LlmUsage.unknown());
    }

    @Test
    void negativeUsageValuesStayUnknownWithoutFailing() {
        responseBody =
                "{\"model\":\"test-model\",\"response\":\"hello.\",\"done\":true,"
                        + "\"prompt_eval_count\":-5,\"eval_count\":-1}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "say hello."));

        assertThat(response.content()).isEqualTo("hello.");
        assertThat(response.usage()).isEqualTo(LlmUsage.unknown());
    }

    @Test
    void nullAndFractionalUsageValuesStayUnknownWithoutFailing() {
        responseBody =
                "{\"model\":\"test-model\",\"response\":\"hello.\",\"done\":true,"
                        + "\"prompt_eval_count\":null,\"eval_count\":12.5}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        LlmResponse response = provider.complete(new LlmRequest("test-model", "say hello."));

        assertThat(response.content()).isEqualTo("hello.");
        assertThat(response.usage()).isEqualTo(LlmUsage.unknown());
    }

    @Test
    void non2xxResponseFailsSafelyWithoutExposingBody() {
        status = 500;
        responseBody = "{\"error\":\"" + ERROR_MARKER + "\"}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        assertThatThrownBy(() -> provider.complete(new LlmRequest("test-model", "summarize this.")))
                .isInstanceOf(OllamaProviderException.class)
                .hasMessage(GENERIC_MESSAGE)
                .hasMessageNotContaining(ERROR_MARKER);
    }

    @Test
    void connectionFailureFailsSafelyWithoutExposingHostOrPort() {
        int port = server.getAddress().getPort();
        server.stop(0);
        OllamaProperties unreachable = properties();
        unreachable.setBaseUrl("http://127.0.0.1:" + port);
        OllamaLlmProvider provider = new OllamaLlmProvider(unreachable);

        assertThatThrownBy(() -> provider.complete(new LlmRequest("test-model", "summarize this.")))
                .isInstanceOf(OllamaProviderException.class)
                .hasMessage(GENERIC_MESSAGE)
                .hasMessageNotContaining(String.valueOf(port));
    }

    @Test
    void malformedResponseFailsSafely() {
        responseBody = "this-is-not-json{{{";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        assertThatThrownBy(() -> provider.complete(new LlmRequest("test-model", "summarize this.")))
                .isInstanceOf(OllamaProviderException.class)
                .hasMessage(GENERIC_MESSAGE)
                .hasMessageNotContaining("this-is-not-json");
    }

    @Test
    void missingResponseFieldFailsSafely() {
        responseBody = "{\"model\":\"test-model\",\"done\":true}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        assertThatThrownBy(() -> provider.complete(new LlmRequest("test-model", "summarize this.")))
                .isInstanceOf(OllamaProviderException.class)
                .hasMessage(GENERIC_MESSAGE);
    }

    @Test
    void timeoutFailsSafely() {
        delayMillis = 5_000;
        OllamaProperties impatient = properties();
        impatient.setReadTimeout(Duration.ofMillis(400));
        OllamaLlmProvider provider = new OllamaLlmProvider(impatient);

        assertThatThrownBy(() -> provider.complete(new LlmRequest("test-model", "summarize this.")))
                .isInstanceOf(OllamaProviderException.class)
                .hasMessage(GENERIC_MESSAGE);
    }

    @Test
    void failureMessageNeverContainsRequestContent() {
        status = 503;
        responseBody = "{\"error\":\"unavailable\"}";
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());
        String distinctiveContent = "distinctive-prompt-body-7c1e55";

        assertThatThrownBy(() -> provider.complete(new LlmRequest("test-model", distinctiveContent)))
                .isInstanceOf(OllamaProviderException.class)
                .hasMessage(GENERIC_MESSAGE)
                .hasMessageNotContaining(distinctiveContent);
    }

    @Test
    void configurationDefaultsAreLocalhostOnly() {
        OllamaProperties defaults = new OllamaProperties();

        assertThat(defaults.getBaseUrl()).isEqualTo("http://localhost:11434");
        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void customBaseUrlIsHonored() {
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        provider.complete(new LlmRequest("test-model", "summarize this."));

        assertThat(path.get()).isEqualTo("/api/generate");
        assertThat(method.get()).isEqualTo("POST");
    }

    @Test
    void blankBaseUrlIsRejected() {
        OllamaProperties broken = properties();
        broken.setBaseUrl("   ");

        assertThatThrownBy(() -> new OllamaLlmProvider(broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl must not be blank");
    }

    @Test
    void nullRequestIsRejected() {
        OllamaLlmProvider provider = new OllamaLlmProvider(properties());

        assertThatThrownBy(() -> provider.complete(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request must not be null");
    }

    @Test
    void holdsNoGatewayAuditPiiOrLoggingCapabilities() {
        for (var field : OllamaLlmProvider.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("ollama provider must not depend on gateway/audit/detection/logging internals")
                    .doesNotContain("audit", "pii", "Detector", "Registry", "Controller");
            assertThat(field.getType().getPackageName())
                    .as("ollama provider must not depend on gateway/audit internals")
                    .doesNotContain(".gateway", ".audit", ".pii");
        }
        assertThat(OllamaLlmProvider.class.getPackageName()).endsWith(".gateway.provider");
    }

    @Test
    void requestAndExceptionCarryNoGatewayInternals() {
        Set<String> requestComponents = Arrays.stream(LlmRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertThat(requestComponents).containsExactlyInAnyOrder("model", "content");
        assertThat(OllamaProviderException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }
}
