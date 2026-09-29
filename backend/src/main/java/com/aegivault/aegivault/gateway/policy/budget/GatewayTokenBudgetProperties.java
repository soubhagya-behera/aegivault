package com.aegivault.aegivault.gateway.policy.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the active gateway token budget:
 * {@code aegivault.gateway.token-budget}, one of
 * {@link GatewayTokenBudgetType}, with
 * {@link GatewayTokenBudgetType#IN_MEMORY} as the default so a fresh checkout
 * needs no Redis server.
 *
 * <p>Kept separate from the global gateway rate limiter's properties and from
 * the request-policy counter's properties: three independent switches, each
 * guarding its own state.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.gateway")
public class GatewayTokenBudgetProperties {

    private GatewayTokenBudgetType tokenBudget = GatewayTokenBudgetType.IN_MEMORY;

    public GatewayTokenBudgetType getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(GatewayTokenBudgetType tokenBudget) {
        this.tokenBudget = tokenBudget;
    }
}
