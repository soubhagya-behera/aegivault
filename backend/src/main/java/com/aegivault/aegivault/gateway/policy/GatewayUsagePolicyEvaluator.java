package com.aegivault.aegivault.gateway.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure decision logic: does one usage snapshot satisfy one policy?
 *
 * <p>It holds no state, performs no I/O, and is not a Spring bean. It has no
 * dependency on repositories, Redis, the rate limiter, controllers,
 * providers, PII detectors, or the audit ledger — it reads two in-memory
 * objects and returns a value. Nothing in the gateway traffic path calls it,
 * so no limit is enforced anywhere yet.
 *
 * <p>Rules, applied to every configured limit rather than short-circuiting on
 * the first one:
 * <ul>
 *   <li>a null limit is unconstrained and is skipped;</li>
 *   <li>a request count above its limit is a violation; exactly at the
 *       limit is not (a limit is the highest permitted value);</li>
 *   <li>a token total above its limit is a violation, but only when the
 *       total is trustworthy;</li>
 *   <li>when a token limit is configured and the total is untrustworthy, the
 *       result is {@code USAGE_UNKNOWN} — never {@code LIMIT_EXCEEDED},
 *       because an unknown total is not evidence of excess, and never
 *       {@code ALLOW}, because the limit's state was not established.</li>
 * </ul>
 *
 * <p>A disabled policy is not evaluated and yields
 * {@link GatewayUsagePolicyDecision.State#INACTIVE}; it is never silently
 * treated as satisfied. Deciding what an inactive policy means is the
 * caller's separate choice, and this class makes no enforcement decision.
 *
 * <p>Exactly one policy is accepted. Choosing between several policies is
 * {@link GatewayUsagePolicyResolver}'s job, and it refuses ambiguous
 * configurations; that logic is deliberately not duplicated here.
 */
public final class GatewayUsagePolicyEvaluator {

    private GatewayUsagePolicyEvaluator() {
        throw new AssertionError("GatewayUsagePolicyEvaluator is a static utility");
    }

    /**
     * Evaluates one policy against one usage snapshot.
     *
     * @param policy the single policy to evaluate, never null
     * @param snapshot the observed usage, never null
     * @return the deterministic decision, never null
     * @throws NullPointerException when the policy or the snapshot is null
     */
    public static GatewayUsagePolicyDecision evaluate(
            GatewayUsagePolicy policy, GatewayUsagePolicyUsageSnapshot snapshot) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!policy.isEnabled()) {
            return GatewayUsagePolicyDecision.inactive();
        }

        // Every configured limit is checked; violations accumulate in
        // GatewayUsagePolicyViolation declaration order, so the result is
        // deterministic without relying on any set's iteration order.
        List<GatewayUsagePolicyViolation> violations = new ArrayList<>();
        if (exceeds(policy.getRequestsPerMinute(), snapshot.requestsInCurrentMinute())) {
            violations.add(GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE);
        }
        if (exceeds(policy.getRequestsPerDay(), snapshot.requestsInCurrentDay())) {
            violations.add(GatewayUsagePolicyViolation.REQUESTS_PER_DAY);
        }

        boolean tokenLimitConfigured = policy.getTokensPerDay() != null;
        boolean tokenUsageUnknown = tokenLimitConfigured && !snapshot.tokenUsageKnown();
        if (tokenLimitConfigured && snapshot.tokenUsageKnown()) {
            if (exceeds(policy.getTokensPerDay(), snapshot.totalTokensInCurrentDay())) {
                violations.add(GatewayUsagePolicyViolation.TOKENS_PER_DAY);
            }
        }

        if (!violations.isEmpty()) {
            // A definite violation outranks token uncertainty, which stays
            // visible through tokenUsageUnknown.
            return GatewayUsagePolicyDecision.limitExceeded(violations, tokenUsageUnknown);
        }
        if (tokenUsageUnknown) {
            return GatewayUsagePolicyDecision.usageUnknown();
        }
        return GatewayUsagePolicyDecision.allow();
    }

    /** A null limit constrains nothing; otherwise only a strict excess counts. */
    private static boolean exceeds(Long limit, long actual) {
        return limit != null && actual > limit;
    }
}