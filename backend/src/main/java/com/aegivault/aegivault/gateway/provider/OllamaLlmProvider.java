package com.aegivault.aegivault.gateway.provider;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Local Ollama {@link LlmProvider} using the Ollama generate API over
 * plain HTTP — no SDK, no new dependencies, just the existing Spring
 * HTTP stack ({@link RestClient}). Maps {@link LlmRequest} model plus
 * content to the Ollama {@code model} plus {@code prompt} fields
 * (non-streaming) and maps the Ollama {@code response} text back into
 * an {@link LlmResponse} for the requested model, plus provider-reported
 * usage from {@code prompt_eval_count} and {@code eval_count} when Ollama
 * actually returns them — absent or invalid usage fields stay unknown
 * rather than estimated. Raw Ollama JSON never leaves this class.
 *
 * <p>Network I/O is this class's explicit responsibility — no other
 * gateway component performs HTTP-provider calls. Request content is
 * never logged and never persisted here. Any failure (connection
 * failure, timeout, non-2xx status, malformed response) surfaces as
 * {@link OllamaProviderException} with a generic message; host/port
 * internals, the raw Ollama body, and request content never enter the
 * exception message.
 *
 * <p>Wired as the single active {@link LlmProvider} bean when
 * {@code aegivault.gateway.provider=OLLAMA} (configuration-driven, never
 * inferred from the model name); the mock stays the default. The provider
 * makes no call at construction time, so the application starts without a
 * live Ollama server and only talks to Ollama when a completion is actually
 * requested.
 */
public class OllamaLlmProvider implements LlmProvider {

    static final String GENERATE_PATH = "/api/generate";

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;

    private final RestClient http;

    private final ObjectMapper json = new ObjectMapper();

    public OllamaLlmProvider(OllamaProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.baseUrl = normalize(properties.getBaseUrl());
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(orDefault(properties.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT));
        requests.setReadTimeout(orDefault(properties.getReadTimeout(), DEFAULT_READ_TIMEOUT));
        this.http = RestClient.builder().requestFactory(requests).build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final String outgoing;
        try {
            outgoing = json.writeValueAsString(
                    Map.of("model", request.model(), "prompt", request.content(), "stream", false));
        } catch (RuntimeException ex) {
            throw new OllamaProviderException("Unable to complete Ollama request.", ex);
        }
        final String raw;
        try {
            raw = http.post()
                    .uri(baseUrl + GENERATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outgoing)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException ex) {
            throw new OllamaProviderException("Unable to complete Ollama request.", ex);
        }
        try {
            JsonNode root = json.readTree(raw);
            JsonNode response = root.path("response");
            if (!response.isTextual()) {
                throw new IllegalStateException("Invalid Ollama response.");
            }
            return new LlmResponse(request.model(), response.asText(), readUsage(root));
        } catch (RuntimeException ex) {
            throw new OllamaProviderException("Unable to complete Ollama request.", ex);
        }
    }

    /**
     * Reads provider-reported usage from an Ollama generate response.
     * Only the documented count fields are mapped ({@code prompt_eval_count}
     * to prompt tokens, {@code eval_count} to completion tokens); Ollama
     * reports no total, so the total always stays unknown rather than
     * derived. Absent, null, non-integral, or negative values stay unknown
     * — never estimated and never a failure.
     */
    private static LlmUsage readUsage(JsonNode root) {
        Long promptTokens = readCount(root.path("prompt_eval_count"));
        Long completionTokens = readCount(root.path("eval_count"));
        if (promptTokens == null && completionTokens == null) {
            return LlmUsage.unknown();
        }
        return new LlmUsage(promptTokens, completionTokens, null);
    }

    private static Long readCount(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isIntegralNumber()) {
            return null;
        }
        long value = node.longValue();
        return value < 0 ? null : value;
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        return normalized;
    }

    private static Duration orDefault(Duration configured, Duration fallback) {
        return configured != null ? configured : fallback;
    }
}
