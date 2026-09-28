package com.aegivault.aegivault.gateway.policy.budget;

import java.util.Objects;

/**
 * What the gateway knows about one completed provider attempt, for settling the
 * token reservation held for it.
 *
 * <p>Exactly two facts are carried: whether a provider response was produced,
 * and the provider-reported total token count when one is known. Nothing is
 * derived from prompt content, response size, character or byte counts, model
 * names, or any other proxy — a total that the provider did not report stays
 * absent rather than becoming a guess.
 *
 * <p>The pairing is validated, because a contradictory pair has no honest
 * settlement: claiming a response with no usage is the genuinely uncertain case
 * and is allowed, but claiming <em>no</em> response alongside a token count is
 * a contradiction, because an unreported count can only come from a response.
 * That case is rejected at construction rather than resolved by guessing which
 * of the two the caller meant.
 *
 * @param outcome whether a provider response was produced, never null
 * @param totalTokens the provider-reported total tokens, null when unknown;
 *        must be null when {@code outcome} is
 *        {@link GatewayProviderPhaseOutcome#NO_RESPONSE} and non-negative
 *        otherwise
 * @throws NullPointerException when {@code outcome} is null
 * @throws IllegalArgumentException when a token count is reported without a
 *         response, or is negative
 */
public record GatewayTokenBudgetSettlement(
        GatewayProviderPhaseOutcome outcome, Long totalTokens) {

    public GatewayTokenBudgetSettlement {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (totalTokens != null) {
            if (totalTokens < 0L) {
                // A negative provider count is not a measurement. Refusing it
                // keeps the budget honest: fabricating a value, or silently
                // treating it as zero, would both misstate real spend.
                throw new IllegalArgumentException("totalTokens must not be negative");
            }
            if (outcome == GatewayProviderPhaseOutcome.NO_RESPONSE) {
                // No response means nothing was generated, so a token count
                // alongside one is incoherent rather than merely unusual.
                throw new IllegalArgumentException(
                        "totalTokens requires a produced provider response");
            }
        }
    }

    /**
     * The provider call failed before any response; nothing was consumed.
     *
     * @return the no-response settlement
     */
    public static GatewayTokenBudgetSettlement noResponse() {
        return new GatewayTokenBudgetSettlement(GatewayProviderPhaseOutcome.NO_RESPONSE, null);
    }

    /**
     * A provider response exists and reported an exact total.
     *
     * @param totalTokens the provider-reported total, non-negative
     * @return the known-usage settlement
     * @throws IllegalArgumentException when the total is negative
     */
    public static GatewayTokenBudgetSettlement withUsage(long totalTokens) {
        return new GatewayTokenBudgetSettlement(
                GatewayProviderPhaseOutcome.RESPONSE_PRODUCED, totalTokens);
    }

    /**
     * A provider response exists but reported no usable token count.
     *
     * @return the unknown-usage settlement
     */
    public static GatewayTokenBudgetSettlement unknownUsage() {
        return new GatewayTokenBudgetSettlement(
                GatewayProviderPhaseOutcome.RESPONSE_PRODUCED, null);
    }

    /** Whether a provider response was produced at all. */
    public boolean hasResponse() {
        return outcome == GatewayProviderPhaseOutcome.RESPONSE_PRODUCED;
    }

    /** Whether the provider reported an exact token total. */
    public boolean hasKnownUsage() {
        return totalTokens != null;
    }
}