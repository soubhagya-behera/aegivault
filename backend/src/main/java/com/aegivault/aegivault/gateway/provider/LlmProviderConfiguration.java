package com.aegivault.aegivault.gateway.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration-driven provider wiring. Exactly one {@link LlmProvider} bean
 * is exposed, chosen once at startup from {@link LlmProviderProperties}:
 *
 * <ul>
 *   <li>{@link LlmProviderType#MOCK} (default) — the active provider is a
 *       {@link MockLlmProvider}; no {@link OllamaLlmProvider} is ever
 *       constructed, so no Ollama HTTP call is possible.</li>
 *   <li>{@link LlmProviderType#OLLAMA} — the active provider is an
 *       {@link OllamaLlmProvider}; no mock is constructed.</li>
 * </ul>
 *
 * <p>There is deliberately no registry, no routing table, and no provider
 * selection during request handling: the switch is exhaustive over
 * {@link LlmProviderType}, so adding a provider type is a compile error
 * until it is wired here. An invalid configured value fails while the
 * properties bean is bound, before this bean (and therefore before the
 * selector) can be created — startup fails naming the property instead of
 * silently falling back to the mock.
 */
@Configuration(proxyBeanMethods = false)
public class LlmProviderConfiguration {

    @Bean
    LlmProvider llmProvider(LlmProviderProperties providerProperties, OllamaProperties ollamaProperties) {
        LlmProviderType provider = providerProperties.getProvider();
        if (provider == null) {
            throw new IllegalStateException("aegivault.gateway.provider must be one of MOCK, OLLAMA");
        }
        return switch (provider) {
            case MOCK -> new MockLlmProvider();
            case OLLAMA -> new OllamaLlmProvider(ollamaProperties);
        };
    }
}
