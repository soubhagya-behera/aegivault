package com.aegivault.aegivault.gateway.policy;

/**
 * The policy request-counter implementations the gateway may be configured
 * with, selected by {@code aegivault.gateway.policy-counter}.
 *
 * <p>This switch is entirely separate from the global gateway rate limiter's
 * {@code aegivault.gateway.rate-limiter} switch. They guard different state
 * and may legitimately be configured differently: a single-instance
 * deployment can run a local policy counter while keeping a distributed
 * global rate limiter, and vice versa.
 */
public enum GatewayUsagePolicyCounterType {

    /**
     * Process-local in-memory counters: deterministic and dependency-free,
     * but not shared between application instances.
     */
    IN_MEMORY,

    /**
     * Shared counters in Redis, for admitting a policy-limited request only
     * once across a multi-instance deployment.
     */
    REDIS
}
