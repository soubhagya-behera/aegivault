package com.aegivault.aegivault.gateway.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single-provider selector: every valid model resolves to the one
 * configured {@link LlmProvider} (currently the mock). Deterministic
 * by design — no registry, no routing table, no network or credential
 * handling. Blank models are rejected before any provider is
 * returned; the completion service fails such selections safely
 * through the existing generic provider-failure behavior.
 */
@Component
@RequiredArgsConstructor
public class DefaultLlmProviderSelector implements LlmProviderSelector {

    private final LlmProvider provider;

    @Override
    public LlmProvider select(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        return provider;
    }
}
