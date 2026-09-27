package com.aegivault.aegivault.gateway;

/**
 * Signals that one authenticated actor exceeded an enabled gateway usage
 * policy's <strong>request limit</strong> (requests per minute or per day).
 * The message is the entire safe HTTP body — it carries no actor, policy id,
 * rejected window, counter value, configured limit, Redis detail, or request
 * content.
 *
 * <p><strong>Deliberately distinct from {@link GatewayRateLimitExceededException}.</strong>
 * The two are independent controls and are never merged: the global gateway
 * rate limit is a fixed platform-wide quota, while this is an owner-scoped
 * persistent policy that a user configures for themselves. They keep separate
 * exception types and separate messages so a client can tell which control
 * stopped a request, and so changing one control can never silently change the
 * other's meaning.
 *
 * <p>This is request admission, not token budgeting: {@code tokensPerDay} is
 * not enforced here, and no token quantity, window, or estimate is exposed or
 * implied.
 *
 * <p>Thrown before request inspection, so a policy-rejected request produces
 * no inspection audit entry, no provider selection or invocation, and no usage
 * record.
 */
public class GatewayUsagePolicyLimitExceededException extends RuntimeException {

    /** The only safe policy-rejection message, shared by throw site and handler. */
    public static final String MESSAGE = "Gateway usage policy limit exceeded.";

    public GatewayUsagePolicyLimitExceededException() {
        super(MESSAGE);
    }
}
