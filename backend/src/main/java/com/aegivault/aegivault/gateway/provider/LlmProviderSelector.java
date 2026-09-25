package com.aegivault.aegivault.gateway.provider;

/**
 * Selects the {@link LlmProvider} for one requested model. The gateway
 * application layer depends on this abstraction, never on a concrete
 * provider. The contract is deliberately minimal: model in, provider
 * out — no provider names in the HTTP contract, no URLs, no
 * credentials, no network configuration, and no vendor-specific
 * concepts.
 */
public interface LlmProviderSelector {

    /**
     * Resolves the provider for one requested model.
     *
     * @param model requested model name, never blank
     * @return the selected provider, never null
     */
    LlmProvider select(String model);
}
