package com.aegivault.aegivault.gateway.policy.budget;

/**
 * The token-budget implementations the gateway may be configured with,
 * selected by {@code aegivault.gateway.token-budget}.
 *
 * <p>This switch is entirely separate from the global gateway rate limiter's
 * {@code aegivault.gateway.rate-limiter} switch and from the request-policy
 * counter's {@code aegivault.gateway.policy-counter} switch. They guard
 * different quantities of different things — a platform-wide request count, an
 * owner-scoped request count, and an owner-scoped daily token budget — and may
 * legitimately be configured differently.
 */
public enum GatewayTokenBudgetType {

    /**
     * Process-local budgets: deterministic and dependency-free, but not shared
     * between application instances.
     */
    IN_MEMORY,

    /**
     * Shared budgets in Redis, so one day's token capacity is held once across
     * a multi-instance deployment.
     */
    REDIS
}
