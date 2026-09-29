package com.aegivault.aegivault.gateway.policy.budget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration-driven wiring for the gateway token budget. Exactly one
 * {@link GatewayTokenBudget} bean is exposed, chosen once at startup from
 * {@link GatewayTokenBudgetProperties}:
 *
 * <ul>
 *   <li>{@link GatewayTokenBudgetType#IN_MEMORY} (default) — the active budget
 *       is an {@link InMemoryGatewayTokenBudget}; Redis is never touched, so no
 *       Redis server is needed.</li>
 *   <li>{@link GatewayTokenBudgetType#REDIS} — the active budget is a
 *       {@link RedisGatewayTokenBudget} over Spring Boot's normal Redis
 *       support, intended for multi-instance enforcement.</li>
 * </ul>
 *
 * <p>The switch is exhaustive over {@link GatewayTokenBudgetType}, so adding a
 * budget type is a compile error until it is wired here. An invalid configured
 * value fails while the properties bean is bound, before this bean can be
 * created, instead of silently falling back.
 *
 * <p><strong>Wiring is not estimation.</strong> Exposing this bean adds no
 * token counting, tokenizer, pricing, or prediction: the amounts themselves
 * still come only from the policy's own
 * {@code reservationTokensPerRequest}, and real usage only from what a provider
 * reports.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayTokenBudgetConfiguration {

    @Bean
    GatewayTokenBudget gatewayTokenBudget(
            GatewayTokenBudgetProperties budgetProperties, StringRedisTemplate redisTemplate) {
        GatewayTokenBudgetType budget = budgetProperties.getTokenBudget();
        if (budget == null) {
            throw new IllegalStateException(
                    "aegivault.gateway.token-budget must be one of IN_MEMORY, REDIS");
        }
        return switch (budget) {
            case IN_MEMORY -> new InMemoryGatewayTokenBudget();
            case REDIS -> new RedisGatewayTokenBudget(redisTemplate);
        };
    }
}
