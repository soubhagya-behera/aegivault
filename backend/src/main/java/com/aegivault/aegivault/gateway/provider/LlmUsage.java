package com.aegivault.aegivault.gateway.provider;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable provider-reported usage metadata for one LLM completion.
 *
 * <p>Carries token counts exactly as reported by the provider, for future
 * gateway accounting. Every field is nullable: a null value means the
 * provider did not supply that count, and unknown usage is preserved as
 * unknown — never estimated from content length, byte size, or division
 * formulas. Has no dependencies beyond the JDK and Jackson annotations,
 * so it never pulls in Redis, persistence, web, rate limiting, audit, or
 * detection types.
 *
 * @param promptTokens provider-reported prompt/input tokens, null when unknown
 * @param completionTokens provider-reported completion/output tokens, null when unknown
 * @param totalTokens provider-reported total tokens, null when unknown
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmUsage(Long promptTokens, Long completionTokens, Long totalTokens) {

    public LlmUsage {
        if (promptTokens != null && promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens must not be negative");
        }
        if (completionTokens != null && completionTokens < 0) {
            throw new IllegalArgumentException("completionTokens must not be negative");
        }
        if (totalTokens != null && totalTokens < 0) {
            throw new IllegalArgumentException("totalTokens must not be negative");
        }
    }

    /** Usage with every count unknown — the default when a provider reports nothing. */
    public static LlmUsage unknown() {
        return new LlmUsage(null, null, null);
    }

    /** True when no count is known. Derived only — never serialized. */
    @JsonIgnore
    public boolean isUnknown() {
        return promptTokens == null && completionTokens == null && totalTokens == null;
    }
}
