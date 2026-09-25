package com.aegivault.aegivault.gateway.provider;

/**
 * The LLM provider implementations the gateway may be configured with.
 *
 * <p>The active type comes from explicit configuration only
 * ({@code aegivault.gateway.provider}, default {@link #MOCK}). It is never
 * inferred from a model name, a URL, request content, or request headers,
 * and the caller of {@code POST /api/gateway/complete} can never choose it.
 */
public enum LlmProviderType {

    /** Deterministic in-process mock: labelled completions, no network, no credentials. */
    MOCK,

    /** Local Ollama server over plain HTTP: localhost configuration only, no API key. */
    OLLAMA
}
