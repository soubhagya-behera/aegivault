package com.aegivault.aegivault.gateway;

import java.time.Duration;

/**
 * The single source of truth for the gateway completion rate-limit
 * policy, shared by every {@link GatewayRateLimiter} implementation so
 * the values are never scattered as literals: 20 completions per actor
 * per 1-minute fixed window, counted under one documented Redis key
 * namespace by the distributed implementation.
 */
public final class GatewayRateLimitPolicy {

    /** Allowed completions per actor per window. */
    public static final int MAX_REQUESTS = 20;

    /** Fixed window length. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Redis key namespace for the distributed limiter. The full key is
     * {@code aegivault:gateway:rate-limit:<actorSubject>}, where the
     * subject is the verified JWT subject (server-issued UUIDs in this
     * project) — never an IP address, request content, or model name.
     * Redis keys are binary-safe, so the subject needs no escaping; the
     * format is deterministic: one actor always maps to one key.
     */
    public static final String KEY_PREFIX = "aegivault:gateway:rate-limit";

    private GatewayRateLimitPolicy() {
    }

    /**
     * Returns the deterministic Redis key for one actor.
     *
     * @param actorSubject verified JWT subject, never blank
     * @return the namespaced key for that actor, never null
     */
    public static String keyFor(String actorSubject) {
        return KEY_PREFIX + ":" + actorSubject;
    }

    /** Returns the window length in milliseconds for Redis TTLs. */
    public static long windowMillis() {
        return WINDOW.toMillis();
    }
}
