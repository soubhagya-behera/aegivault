package com.aegivault.aegivault.gateway.policy;

/**
 * One limit a {@link GatewayUsagePolicy} declares and an
 * {@link GatewayUsagePolicyEvaluator} can report as violated.
 *
 * <p>The declaration order below <em>is</em> the reporting order: an
 * evaluation that trips several limits always lists them in exactly this
 * sequence, so the result is deterministic and never depends on set
 * iteration order.
 */
public enum GatewayUsagePolicyViolation {

    /** The actor's requests in the current minute exceed the declared limit. */
    REQUESTS_PER_MINUTE,

    /** The actor's requests in the current day exceed the declared limit. */
    REQUESTS_PER_DAY,

    /** The actor's tokens in the current day exceed the declared limit. */
    TOKENS_PER_DAY
}