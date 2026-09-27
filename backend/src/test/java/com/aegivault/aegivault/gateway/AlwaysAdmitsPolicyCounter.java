package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterRequest;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterResult;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterUnavailableException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterWindow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A policy counter that admits everything, for gateway tests whose subject is
 * not policy enforcement.
 *
 * <p>It exists because {@link GatewayUsagePolicyCounter} has two operations
 * and so is not a functional interface, and because a pre-existing gateway test
 * should not have to reason about policy limits at all: it must simply behave
 * as it did before policy enforcement was wired in, which is what "the actor has
 * no effective policy, so nothing constrains the request" means at runtime.
 *
 * <p>It still records what it was asked, so a test that does care about policy
 * consumption can assert on {@link #requests()}.
 */
final class AlwaysAdmitsPolicyCounter implements GatewayUsagePolicyCounter {

    private final List<GatewayUsagePolicyCounterRequest> requests =
            Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean tryConsume(String actorSubject, GatewayUsagePolicyCounterWindow window, long limit) {
        return tryConsume(GatewayUsagePolicyCounterRequest.of(
                actorSubject, java.time.Instant.now(), window, limit))
                .isAllowed();
    }

    @Override
    public GatewayUsagePolicyCounterResult tryConsume(GatewayUsagePolicyCounterRequest request) {
        requests.add(request);
        return GatewayUsagePolicyCounterResult.allowed();
    }

    /** The requests this counter was asked to consume, in order. */
    List<GatewayUsagePolicyCounterRequest> requests() {
        return List.copyOf(requests);
    }

    /** How many request units were consumed in total. */
    long consumed() {
        return requests.size();
    }

    /** A counter that refuses every consume, standing in for a spent limit. */
    static final class AlwaysRejectingPolicyCounter implements GatewayUsagePolicyCounter {

        @Override
        public boolean tryConsume(String actorSubject, GatewayUsagePolicyCounterWindow window, long limit) {
            return tryConsume(GatewayUsagePolicyCounterRequest.of(
                    actorSubject, java.time.Instant.now(), window, limit))
                    .isAllowed();
        }

        @Override
        public GatewayUsagePolicyCounterResult tryConsume(GatewayUsagePolicyCounterRequest request) {
            return GatewayUsagePolicyCounterResult.rejected(request.windowsInEvaluationOrder().get(0));
        }
    }

    /**
     * A counter whose backing store is unreachable, standing in for a Redis
     * outage.
     */
    static final class UnavailablePolicyCounter implements GatewayUsagePolicyCounter {

        @Override
        public boolean tryConsume(String actorSubject, GatewayUsagePolicyCounterWindow window, long limit) {
            return tryConsume(GatewayUsagePolicyCounterRequest.of(
                    actorSubject, java.time.Instant.now(), window, limit))
                    .isAllowed();
        }

        @Override
        public GatewayUsagePolicyCounterResult tryConsume(GatewayUsagePolicyCounterRequest request) {
            throw new GatewayUsagePolicyCounterUnavailableException(
                    new RuntimeException("redis-connection-refused-9z"));
        }
    }
}


