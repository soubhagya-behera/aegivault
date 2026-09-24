package com.aegivault.aegivault.gateway;

/**
 * Immutable gateway security policy for one inspection: which finding
 * categories block the request. A pure value, deliberately disconnected
 * from the persistent {@code SanitizationPolicy} — wiring the gateway to
 * stored policies is a later integration decision, not part of this
 * foundation.
 *
 * @param blockOnPii whether detected PII blocks the request
 * @param blockOnSecrets whether detected secrets block the request
 */
public record GatewaySecurityPolicy(boolean blockOnPii, boolean blockOnSecrets) {

    /** Blocks on both PII and secrets. */
    public static GatewaySecurityPolicy strict() {
        return new GatewaySecurityPolicy(true, true);
    }

    /** Reports findings but never blocks; observation without enforcement. */
    public static GatewaySecurityPolicy monitoring() {
        return new GatewaySecurityPolicy(false, false);
    }
}
