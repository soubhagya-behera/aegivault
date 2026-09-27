package com.aegivault.aegivault.gateway.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single {@link GatewayUsagePolicyEnforcementService} used by the
 * gateway completion path.
 *
 * <p>The service composes exactly two already-configured collaborators — the
 * {@link GatewayUsagePolicyResolver} and the {@link GatewayUsagePolicyCounter}
 * (itself selected by {@code aegivault.gateway.policy-counter}) — so nothing
 * new is configurable here. Exposing it as exactly one bean keeps the
 * dependency direction one-way: the completion service depends on the
 * enforcement service and never on the resolver, the counter, or any counter
 * implementation.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayUsagePolicyEnforcementConfiguration {

    @Bean
    GatewayUsagePolicyEnforcementService gatewayUsagePolicyEnforcementService(
            GatewayUsagePolicyResolver resolver, GatewayUsagePolicyCounter counter) {
        return new GatewayUsagePolicyEnforcementService(resolver, counter);
    }
}
