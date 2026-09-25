package com.aegivault.aegivault.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration-driven rate-limiter wiring. Exactly one
 * {@link GatewayRateLimiter} bean is exposed, chosen once at startup
 * from {@link RateLimiterProperties}:
 *
 * <ul>
 *   <li>{@link RateLimiterType#IN_MEMORY} (default) — the active limiter
 *       is an {@link InMemoryGatewayRateLimiter}; Redis is never
 *       touched, so no Redis server is needed.</li>
 *   <li>{@link RateLimiterType#REDIS} — the active limiter is a
 *       {@link RedisGatewayRateLimiter} over Spring Boot's normal Redis
 *       support; no in-memory state is used.</li>
 * </ul>
 *
 * <p>The switch is exhaustive over {@link RateLimiterType}, so adding a
 * limiter type is a compile error until it is wired here. Callers such
 * as {@link GatewayCompletionService} depend only on the
 * {@link GatewayRateLimiter} abstraction and never change with the
 * selection. An invalid configured value fails while the properties
 * bean is bound, before this bean can be created — startup fails naming
 * the property instead of silently falling back.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfiguration {

    @Bean
    GatewayRateLimiter gatewayRateLimiter(
            RateLimiterProperties limiterProperties, StringRedisTemplate redisTemplate) {
        RateLimiterType limiter = limiterProperties.getRateLimiter();
        if (limiter == null) {
            throw new IllegalStateException("aegivault.gateway.rate-limiter must be one of IN_MEMORY, REDIS");
        }
        return switch (limiter) {
            case IN_MEMORY -> new InMemoryGatewayRateLimiter();
            case REDIS -> new RedisGatewayRateLimiter(redisTemplate);
        };
    }
}
