package com.aegivault.aegivault.gateway.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * Answers one question: what is the current gateway usage policy decision for
 * this actor, at this instant?
 *
 * <p>It is the first place the three existing pieces are composed:
 * <ol>
 *   <li>{@link GatewayUsagePolicyResolver} decides whether the actor has a
 *       policy, and refuses to guess if more than one is enabled;</li>
 *   <li>{@link GatewayUsagePolicyUsageSnapshotProvider} reads what that same
 *       actor has actually used;</li>
 *   <li>{@link GatewayUsagePolicyEvaluator} — pure, and the only place a
 *       limit is ever compared — decides whether that policy is satisfied.</li>
 * </ol>
 *
 * <p><strong>No decision logic is duplicated here.</strong> This class
 * contains no limit comparison, no violation ordering, and no rule that could
 * produce a state the evaluator would not have produced. It only orders the
 * three calls and projects the evaluator's answer onto
 * {@link GatewayUsagePolicyDecisionOutcome}. If it disagreed with the
 * evaluator, the evaluator would still be the one doing the deciding.
 *
 * <p><strong>The three outcomes that are not a limit decision.</strong>
 * <ul>
 *   <li>no enabled policy →
 *       {@link GatewayUsagePolicyDecisionOutcome.State#NO_POLICY}, a normal,
 *       non-error outcome that is never reported as {@code ALLOW};
 *   <li>several enabled policies → {@link GatewayUsagePolicyAmbiguousException}
 *       propagates unchanged. The ambiguity is not converted into a decision,
 *       not swallowed, and above all not resolved by picking one policy: the
 *       wrong pick would enforce the wrong limit. No usage is even read, so no
 *       work is done on a configuration that cannot produce an answer;
 *   <li>a broken dependency contract → a null resolution, policy, or snapshot
 *       is rejected loudly instead of being treated as "no policy" or
 *       "allowed".</li>
 * </ul>
 *
 * <p><strong>Determinism.</strong> The instant is supplied by the caller and
 * forwarded unchanged. This class never calls {@code Instant.now()}, so the
 * same actor at the same instant always produces the same answer — which is
 * also what makes the composition testable without a clock. The same trimmed
 * actor is used for both the policy lookup and the usage lookup, so a policy
 * can never be evaluated against another actor's usage.
 *
 * <p><strong>Dependency direction.</strong> Exactly two collaborators — the
 * resolver and the snapshot provider — plus the static evaluator. It holds no
 * reference to {@code GatewayCompletionService}, {@code GatewayRateLimiter},
 * Redis, providers, repositories, controllers, or audit services, so there is
 * no path from here back into the gateway traffic flow. It is deliberately
 * not a Spring bean, so it cannot be picked up by the application context and
 * cannot acquire runtime wiring later without an explicit decision.
 *
 * <p><strong>Nothing is enforced by this.</strong> It reports a decision and
 * grants nothing: no request is blocked, no counter is written, no capacity is
 * reserved, and no check-and-consume step exists. Concurrent enforcement —
 * making two simultaneous requests observe each other's usage — is explicitly
 * out of scope, because two independent reads are not an atomic check. No
 * gateway traffic path calls this service yet, so it currently has zero effect
 * on live traffic and rate limiting remains governed solely by
 * {@code GatewayRateLimiter} configuration.
 *
 * <p><strong>What it returns.</strong> A {@link GatewayUsagePolicyDecisionOutcome}:
 * a state plus already-computed violation metadata. It never returns the actor
 * subject, the policy owner, a policy label or id, a raw database row,
 * provider content, request or response content, a secret, or any PII.
 */
public class GatewayUsagePolicyDecisionService {

    private final GatewayUsagePolicyResolver resolver;

    private final GatewayUsagePolicyUsageSnapshotProvider snapshots;

    /**
     * @param resolver supplies the actor's single effective policy; never null
     * @param snapshots supplies the actor's observed usage; never null
     * @throws NullPointerException when either collaborator is null
     */
    public GatewayUsagePolicyDecisionService(
            GatewayUsagePolicyResolver resolver, GatewayUsagePolicyUsageSnapshotProvider snapshots) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    }

    /**
     * Composes resolution, usage collection, and evaluation into one decision
     * for one actor at one instant.
     *
     * @param actorSubject authenticated actor, never blank; trimmed exactly
     *        like the resolver and the snapshot provider trim it, so all three
     *        see the same value
     * @param now the instant to decide at, never null and never defaulted to
     *        the current time
     * @return the composed decision, never null
     * @throws IllegalArgumentException when {@code actorSubject} is blank or
     *         {@code now} is null
     * @throws GatewayUsagePolicyAmbiguousException when the actor has more
     *         than one enabled policy; propagated unchanged from the resolver
     * @throws NullPointerException when a collaborator breaks its contract by
     *         returning null
     */
    public GatewayUsagePolicyDecisionOutcome decide(String actorSubject, Instant now) {
        String actor = requireActor(actorSubject);
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }

        GatewayUsagePolicyResolution resolution =
                Objects.requireNonNull(resolver.resolve(actor), "resolver must not return a null resolution");
        if (resolution instanceof GatewayUsagePolicyResolution.None) {
            // No policy means no evaluation: reading usage here would be work
            // whose result could never change the answer.
            return GatewayUsagePolicyDecisionOutcome.noPolicy();
        }
        // Any other resolution must carry a policy; a resolution that is
        // neither None nor a policy is a broken contract, not a silent
        // fallback to "no policy".
        GatewayUsagePolicy policy = Objects.requireNonNull(
                resolution.effectivePolicy().orElse(null), "resolution must carry a policy");

        GatewayUsagePolicyUsageSnapshot snapshot = Objects.requireNonNull(
                snapshots.snapshotFor(actor, now), "snapshots must not return a null snapshot");

        return GatewayUsagePolicyDecisionOutcome.of(GatewayUsagePolicyEvaluator.evaluate(policy, snapshot));
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
