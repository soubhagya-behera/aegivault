package com.aegivault.aegivault.gateway;

/**
 * Signals that one authenticated actor exhausted the token capacity their
 * enabled gateway usage policy reserves for the current UTC day, so this
 * request's reservation was refused. The message is the entire safe HTTP body
 * — it carries no actor, policy id, configured limit, reserved or remaining
 * tokens, current usage, reservation id, Redis detail, or request content.
 *
 * <p><strong>Distinct from every other refusal in the gateway.</strong> The
 * global {@link GatewayRateLimitExceededException} is a fixed platform-wide
 * request quota, {@link GatewayUsagePolicyLimitExceededException} is the same
 * actor's <em>request</em> policy, and this is that same policy's <em>token</em>
 * half. They keep separate types and separate messages so a client can tell
 * which control stopped the request, and so exhausting one can never be
 * reported as if another had.
 *
 * <p><strong>A rejection, never a failure to decide.</strong> An unavailable
 * budget raises
 * {@link com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementException}
 * instead, so "budget exhausted" and "could not check the budget" can never be
 * confused; the first is a 429, the second a 500.
 *
 * <p>Thrown after request inspection and provider selection but before the
 * provider is invoked, so a token-rejected request never reaches a provider,
 * records no usage, and never produces a provider-response audit event. The
 * already-recorded request-inspection audit entry is left exactly as it is.
 */
public class GatewayTokenBudgetLimitExceededException extends RuntimeException {

    /** The only safe token-budget rejection message, shared by throw site and handler. */
    public static final String MESSAGE = "Gateway token budget exceeded.";

    public GatewayTokenBudgetLimitExceededException() {
        super(MESSAGE);
    }
}
