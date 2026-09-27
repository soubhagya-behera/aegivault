package com.aegivault.aegivault.gateway.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the gateway usage-policy request counter:
 * {@code aegivault.gateway.policy-counter}, one of
 * {@link GatewayUsagePolicyCounterType}, with
 * {@link GatewayUsagePolicyCounterType#IN_MEMORY} as the default so a fresh
 * checkout needs no Redis server and the normal application requires no
 * additional configuration at all.
 *
 * <p>Kept separate from the global gateway rate limiter's own properties: the
 * two switches are independent and neither reads or writes the other.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.gateway")
public class GatewayUsagePolicyCounterProperties {

    private GatewayUsagePolicyCounterType policyCounter = GatewayUsagePolicyCounterType.IN_MEMORY;

    public GatewayUsagePolicyCounterType getPolicyCounter() {
        return policyCounter;
    }

    public void setPolicyCounter(GatewayUsagePolicyCounterType policyCounter) {
        this.policyCounter = policyCounter;
    }
}
