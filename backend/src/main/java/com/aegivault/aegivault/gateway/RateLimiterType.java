package com.aegivault.aegivault.gateway;

/**
 * The rate-limiter implementations the gateway may be configured with.
 *
 * <p>The active type comes from explicit configuration only
 * ({@code aegivault.gateway.rate-limiter}, default {@link #IN_MEMORY}).
 */
public enum RateLimiterType {

    /** Process-local in-memory fixed window: no Redis, no shared state. */
    IN_MEMORY,

    /** Shared fixed-window counters in Redis for multi-instance enforcement. */
    REDIS
}
