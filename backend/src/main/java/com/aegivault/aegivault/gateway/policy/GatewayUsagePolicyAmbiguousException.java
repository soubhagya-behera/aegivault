package com.aegivault.aegivault.gateway.policy;

/**
 * Thrown when an actor has more than one enabled gateway usage policy, so no
 * single effective policy can be determined.
 *
 * <p>This is a configuration error and it is treated as one. Picking the
 * newest, oldest, tightest, loosest, or alphabetically first policy would be
 * a silent guess, and enforcing the wrong limit is worse than refusing to
 * resolve at all — so resolution fails instead.
 *
 * <p>The message is fixed and safe: it reveals no owner subject, no policy
 * id, no policy label, no count, and no database detail. A caller learns
 * only that its configuration is ambiguous.
 */
public class GatewayUsagePolicyAmbiguousException extends RuntimeException {

    public GatewayUsagePolicyAmbiguousException() {
        super("Multiple enabled gateway usage policies are configured.");
    }
}