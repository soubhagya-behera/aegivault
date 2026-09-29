package com.aegivault.aegivault.gateway.policy.budget;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single {@link GatewayTokenBudgetEnforcementService} and
 * {@link GatewayTokenBudgetSettlementService} used by the gateway completion
 * path.
 *
 * <p>Both services compose only already-configured collaborators — the
 * {@link GatewayUsagePolicyResolver}, and the one
 * {@link GatewayTokenBudget} selected by
 * {@code aegivault.gateway.token-budget} — so nothing new is configurable
 * here. Exposing exactly one bean of each keeps the dependency direction
 * one-way: the completion service depends on the two coordinators and never on
 * the resolver, the budget, a budget implementation, or Redis.
 *
 * <p>The two coordinators stay separate beans because they run at different
 * times — one before the provider is invoked, one after — and answer different
 * questions about the same hold.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayTokenBudgetEnforcementConfiguration {

    @Bean
    GatewayTokenBudgetEnforcementService gatewayTokenBudgetEnforcementService(
            GatewayUsagePolicyResolver resolver, GatewayTokenBudget budget) {
        return new GatewayTokenBudgetEnforcementService(resolver, budget);
    }

    @Bean
    GatewayTokenBudgetSettlementService gatewayTokenBudgetSettlementService(GatewayTokenBudget budget) {
        return new GatewayTokenBudgetSettlementService(budget);
    }
}
