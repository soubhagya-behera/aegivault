package com.aegivault.aegivault.gateway.policy;

/**
 * Signals that a policy request limit could not be enforced — the underlying
 * {@link GatewayUsagePolicyCounter} was unavailable or failed. The request was
 * therefore neither admitted nor rejected: nothing was known.
 *
 * <p><strong>Fail closed, and stay distinguishable from a rejection.</strong>
 * Returning a rejection here would tell the caller a limit was exceeded when
 * the truth is that no answer was available, which would surface a wrong
 * reason to the user and could mask an outage. The separate type keeps
 * "limit exceeded" and "could not check" apart for the future integration to
 * map to its own responses.
 *
 * <p>The message is fixed and safe: it names no Redis host, port, key,
 * counter value, actor subject, policy detail, or underlying exception text.
 * The cause is retained for server logs only. No HTTP status is chosen here;
 * this milestone has no controller integration.
 */
public class GatewayUsagePolicyEnforcementException extends RuntimeException {

    /** The only safe enforcement-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to enforce gateway usage policy.";

    public GatewayUsagePolicyEnforcementException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
