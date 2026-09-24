package com.aegivault.aegivault.gateway.provider;

/**
 * Immutable outcome of one LLM provider completion: the responding model
 * and its completion text. Minimal by design — no token accounting, no
 * latency metrics, no usage billing, no streaming, no tool calls.
 *
 * @param model model that produced the completion, never blank
 * @param content completion text, never null
 */
public record LlmResponse(String model, String content) {

    public LlmResponse {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
    }
}
