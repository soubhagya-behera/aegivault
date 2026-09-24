package com.aegivault.aegivault.gateway.provider;

/**
 * Immutable input to one LLM provider completion: model and content only.
 *
 * <p>Deliberately carries no gateway internals: no actor subject, no JWT,
 * no audit data, no PII findings, and no policy controls. Provider
 * contracts are plain Java — independent of HTTP and Spring MVC — so a
 * future gateway can forward an approved request without coupling to any
 * vendor SDK.
 *
 * @param model model name the completion targets, never blank
 * @param content prompt text to complete; null is normalized to empty,
 *        consistent with the gateway inspection request
 */
public record LlmRequest(String model, String content) {

    public LlmRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (content == null) {
            content = "";
        }
    }
}
