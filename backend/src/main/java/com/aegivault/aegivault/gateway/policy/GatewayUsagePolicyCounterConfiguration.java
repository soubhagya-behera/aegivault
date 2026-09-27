package com.aegivault.aegivault.gateway.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration-driven wiring for the gateway usage-policy request counter.
 * Exactly one {@link GatewayUsagePolicyCounter} bean is exposed, chosen once
 * at startup from {@link GatewayUsagePolicyCounterProperties}:
 *
 * <ul>
 *   <li>{@link GatewayUsagePolicyCounterType#IN_MEMORY} (default) — the
 *       active counter is an {@link InMemoryGatewayUsagePolicyCounter}; Redis
 *       is never touched, so no Redis server is needed.</li>
 *   <li>{@link GatewayUsagePolicyCounterType#REDIS} — the active counter is
 *       a {@link RedisGatewayUsagePolicyCounter} over Spring Boot's normal
 *       Redis support, intended for multi-instance enforcement.</li>
 * </ul>
 *
 * <p>The switch is exhaustive over {@link GatewayUsagePolicyCounterType}, so
 * adding a counter type is a compile error until it is wired here. An invalid
 * configured value fails while the properties bean is bound, before this bean
 * can be created, instead of silently falling back.
 *
 * <p><strong>Wiring is not enforcement.</strong> Exposing this bean connects
 * nothing to live traffic: no gateway code path calls
 * {@link GatewayUsagePolicyCounter} yet, so the counter has zero effect on
 * real requests. It is an available primitive for a later milestone. The
 * global gateway rate limiter's own configuration
 * ({@code aegivault.gateway.rate-limiter}) is untouched and independent.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayUsagePolicyCounterConfiguration {

    @Bean
    GatewayUsagePolicyCounter gatewayUsagePolicyCounter(
            GatewayUsagePolicyCounterProperties counterProperties, StringRedisTemplate redisTemplate) {
        GatewayUsagePolicyCounterType counter = counterProperties.getPolicyCounter();
        if (counter == null) {
            throw new IllegalStateException(
                    "aegivault.gateway.policy-counter must be one of IN_MEMORY, REDIS");
        }
        return switch (counter) {
            case IN_MEMORY -> new InMemoryGatewayUsagePolicyCounter();
            case REDIS -> new RedisGatewayUsagePolicyCounter(redisTemplate);
        };
    }
}
