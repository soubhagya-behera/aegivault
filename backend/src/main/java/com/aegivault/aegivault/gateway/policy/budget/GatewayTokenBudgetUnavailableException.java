package com.aegivault.aegivault.gateway.policy.budget;

/**
 * Signals that an atomic daily token-budget reservation could not be completed
 * — for example the configured Redis server is unreachable or returned nothing
 * usable. The budget could not establish whether the reservation fits, so no
 * capacity may be assumed available.
 *
 * <p><strong>Fail closed, deliberately.</strong> Treating an unavailable budget
 * as "reservable" would turn an infrastructure outage into unlimited token
 * spend, which is precisely when a limit matters most. This is why it is a
 * distinct type rather than a {@code REJECTED} result: a rejection means "the
 * day's budget is spoken for", an error means "the budget is unknown", and a
 * caller must not confuse the two. A rejection that reports a spent budget
 * wrongly, or a silent admission that reports an available one, would both be
 * worse than an explicit failure.
 *
 * <p>The message is fixed and safe. It carries no Redis host, port,
 * credentials, key, actor subject, token count, limit, or underlying exception
 * text; the cause is retained for server logs only.
 *
 * <p>No HTTP status is chosen here. This milestone has no controller or gateway
 * integration, so translating this into a response is a later decision.
 */
public class GatewayTokenBudgetUnavailableException extends RuntimeException {

    /** The only safe unavailability message, shared by every throw site. */
    public static final String MESSAGE = "Unable to reserve gateway token budget.";

    public GatewayTokenBudgetUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}