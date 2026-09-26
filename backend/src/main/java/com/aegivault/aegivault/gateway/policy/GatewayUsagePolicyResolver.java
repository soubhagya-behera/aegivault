package com.aegivault.aegivault.gateway.policy;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the single effective gateway usage policy for one authenticated
 * actor, deterministically and without guessing.
 *
 * <p>The rule is deliberately strict:
 * <ol>
 *   <li>only policies owned by that actor are candidates;</li>
 *   <li>only <em>enabled</em> policies are candidates;</li>
 *   <li>exactly one candidate → that policy is the effective one;</li>
 *   <li>no candidate → the no-policy outcome, which is normal, not an error;</li>
 *   <li>more than one candidate → {@link GatewayUsagePolicyAmbiguousException}.</li>
 * </ol>
 *
 * <p>Ambiguity is an error on purpose. Choosing the newest, oldest, largest,
 * smallest, or alphabetically first candidate would be an arbitrary pick,
 * and a wrong pick would enforce the wrong limit. Refusing to resolve is the
 * only safe answer, so no tie-break rule exists here and the query's
 * ordering is never consulted to break a tie.
 *
 * <p>Dependency direction is one-way and minimal: this resolver depends on
 * {@link GatewayUsagePolicyRepository} and nothing else. It does not depend
 * on the gateway completion service, the rate limiter, Redis, providers, PII
 * detectors, the audit ledger, or any controller.
 *
 * <p><strong>Nothing enforces the result yet.</strong> No gateway traffic
 * path calls this resolver, so resolution has zero runtime effect on
 * requests, and rate limiting remains governed solely by
 * {@code GatewayRateLimiter} configuration.
 */
@Service
@RequiredArgsConstructor
public class GatewayUsagePolicyResolver {

    private final GatewayUsagePolicyRepository policies;

    /**
     * Resolves the actor's effective policy.
     *
     * @param actorSubject authenticated actor, never blank; trimmed exactly
     *        like {@link GatewayUsagePolicyService} does
     * @return the resolved policy, or the no-policy outcome
     * @throws IllegalArgumentException when {@code actorSubject} is blank
     * @throws GatewayUsagePolicyAmbiguousException when the actor has more
     *         than one enabled policy
     */
    @Transactional(readOnly = true)
    public GatewayUsagePolicyResolution resolve(String actorSubject) {
        String actor = requireActor(actorSubject);
        List<GatewayUsagePolicy> candidates =
                policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(actor);
        if (candidates.size() > 1) {
            throw new GatewayUsagePolicyAmbiguousException();
        }
        return candidates.isEmpty()
                ? GatewayUsagePolicyResolution.none()
                : GatewayUsagePolicyResolution.resolved(candidates.get(0));
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}