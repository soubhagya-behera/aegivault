package com.aegivault.aegivault.gateway.policy;

/**
 * Signals that an atomic policy request-counter operation could not be
 * completed — for example the configured Redis server is unreachable or
 * returned nothing usable. The counter could not establish whether the
 * request fits within the limit, so the request must not be admitted.
 *
 * <p><strong>Fail closed, deliberately.</strong> The alternative — treating an
 * unavailable counter as "allowed" — would turn an infrastructure outage into
 * unlimited policy usage, which is precisely when a limit matters most. This
 * is why it is a distinct type rather than a {@code false} return: a
 * {@code false} means "the limit is spent", an error means "the limit is
 * unknown", and a caller must not confuse the two.
 *
 * <p>The message is fixed and safe. It carries no Redis host, port,
 * credentials, key, counter value, actor subject, or underlying exception
 * text; the cause is retained for server logs only.
 *
 * <p>No HTTP status is chosen here. This milestone has no controller
 * integration, so translating this into a response is a later decision.
 */
public class GatewayUsagePolicyCounterUnavailableException extends RuntimeException {

    /** The only safe unavailability message, shared by every throw site. */
    public static final String MESSAGE = "Unable to check gateway usage policy limit.";

    public GatewayUsagePolicyCounterUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
