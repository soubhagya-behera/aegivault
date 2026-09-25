package com.aegivault.aegivault.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the active gateway rate limiter:
 * {@code aegivault.gateway.rate-limiter}, one of {@link RateLimiterType},
 * with {@link RateLimiterType#IN_MEMORY} as the default so a fresh
 * checkout needs no Redis server.
 *
 * <p>An unsupported value fails fast while this property is bound at
 * startup (the failure names the property and carries no secrets)
 * instead of silently falling back to the in-memory limiter.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.gateway")
public class RateLimiterProperties {

    private RateLimiterType rateLimiter = RateLimiterType.IN_MEMORY;

    public RateLimiterType getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(RateLimiterType rateLimiter) {
        this.rateLimiter = rateLimiter;
    }
}
