package com.aegivault.aegivault.gateway.provider;

/**
 * Immutable outcome of one LLM provider completion: the responding model,
 * its completion text, and optional provider-reported usage metadata.
 * Minimal by design — no latency metrics, no usage billing, no streaming,
 * no tool calls.
 *
 * @param model model that produced the completion, never blank
 * @param content completion text, never null
 * @param usage provider-reported token usage, never null (unknown when the
 *        provider supplied no counts — never estimated)
 */
public record LlmResponse(String model, String content, LlmUsage usage) {

    public LlmResponse {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (usage == null) {
            usage = LlmUsage.unknown();
        }
    }

    /**
     * Creates a response with unknown usage, preserving the original
     * two-argument contract for providers that report no counts.
     */
    public LlmResponse(String model, String content) {
        this(model, content, LlmUsage.unknown());
    }
}
