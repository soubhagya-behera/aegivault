package com.aegivault.aegivault.gateway.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the active gateway provider:
 * {@code aegivault.gateway.provider}, one of {@link LlmProviderType}, with
 * {@link LlmProviderType#MOCK} as the default so a fresh checkout needs no
 * configuration and no Ollama server.
 *
 * <p>Provider selection is configuration-driven only — never inferred from
 * model names, URLs, content, or headers. An unsupported value fails fast
 * while this property is bound at startup (the failure names the property
 * and carries no secrets) instead of silently falling back to the mock.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.gateway")
public class LlmProviderProperties {

    private LlmProviderType provider = LlmProviderType.MOCK;

    public LlmProviderType getProvider() {
        return provider;
    }

    public void setProvider(LlmProviderType provider) {
        this.provider = provider;
    }
}
