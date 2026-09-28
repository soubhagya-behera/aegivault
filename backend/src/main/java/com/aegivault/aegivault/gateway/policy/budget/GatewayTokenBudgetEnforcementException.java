package com.aegivault.aegivault.gateway.policy.budget;

/**
 * Signals that a token-budget reservation could not be decided — the
 * {@link GatewayTokenBudget} was unavailable or failed. The request was
 * therefore neither reserved nor rejected: nothing was known.
 *
 * <p><strong>Fail closed, and stay distinguishable from a rejection.</strong>
 * Reporting this as a rejection would tell the caller the daily token budget
 * was exhausted when the truth is that no answer was available, which would
 * surface a wrong reason and could mask an outage that lets traffic through
 * unmetered. The separate type keeps "budget exceeded" and "could not check"
 * apart.
 *
 * <p>It is deliberately distinct from
 * {@link GatewayUsagePolicyEnforcementException}, which reports the same class
 * of failure for <em>request</em> limits: a caller must be able to tell which
 * control could not be evaluated, and collapsing them would hide a partial
 * outage.
 *
 * <p>The message is fixed and safe: it names no Redis host, port, key, actor
 * subject, token count, limit, or underlying exception text. The cause is
 * retained for server logs only. No HTTP status is chosen here — this
 * coordinator has no controller and is not yet in the request path.
 */
public class GatewayTokenBudgetEnforcementException extends RuntimeException {

    /** The only safe enforcement-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to enforce gateway token budget.";

    public GatewayTokenBudgetEnforcementException(Throwable cause) {
        super(MESSAGE, cause);
    }
}