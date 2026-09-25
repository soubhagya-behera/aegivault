package com.aegivault.aegivault.gateway.usage;

/**
 * Outcome of one gateway provider invocation for usage accounting: what
 * happened to a provider response that already exists.
 *
 * <p>Exactly two states exist. {@code DELIVERED} is a clean provider
 * response that passed response inspection and reached the client.
 * {@code SECURITY_BLOCKED} is a provider response stopped by the existing
 * response inspection — recorded because the blocked call may still have
 * consumed provider tokens.
 *
 * <p>There is deliberately no outcome for request-side security BLOCK,
 * rate-limit rejection, or provider invocation failure: no provider
 * response — and therefore no provider usage — exists in those cases, so
 * nothing is recorded.
 */
public enum GatewayUsageOutcome {
    DELIVERED,
    SECURITY_BLOCKED
}
