package com.aegivault.aegivault.gateway.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * Enforces one actor's gateway usage policy <strong>request limits</strong> by
 * atomically consuming capacity from the {@link GatewayUsagePolicyCounter}.
 *
 * <p>This is the runtime half of policy enforcement, and it is deliberately
 * separate from the observational half.
 * {@link GatewayUsagePolicyDecisionService} answers "were this actor's limits
 * satisfied by the usage recorded so far?" using the pure
 * {@link GatewayUsagePolicyEvaluator} over a persisted snapshot. This service
 * answers the different, pre-flight question "may this request proceed?",
 * and it does so through the atomic counter. The evaluator is never used
 * here: its snapshot is inherently after the fact, so two simultaneous
 * requests would both read the same historical count and both conclude there
 * was room. The counter's single indivisible operation is what makes the
 * admission decision correct under concurrency.
 *
 * <p><strong>Only request limits are enforced.</strong> {@code
 * requestsPerMinute} and {@code requestsPerDay} are consumed through the
 * counter; {@code tokensPerDay} is deliberately not read at all. Token usage
 * is only known after a provider response, while admission happens before
 * provider invocation, so there is no truthful token number to compare here.
 * No estimate, max-token assumption, character-to-token conversion, or
 * response-size heuristic is used. Token enforcement needs an explicit
 * reservation/accounting design in a later milestone.
 *
 * <p><strong>All configured request limits must admit the request.</strong> A
 * policy with both limits set is admitted only if both consume successfully.
 * Evaluation order is fixed and deterministic: the day window first, then the
 * minute window. A null limit is unconstrained and is skipped entirely, so a
 * minute-only policy never touches the day counter and vice versa.
 *
 * <p><strong>Short-circuits, and never partially consumes after a failure.</strong>
 * The first limit that rejects or fails stops the call immediately, so no
 * later counter is touched.
 *
 * <p><strong>Multi-limit consumption is NOT transactional — a known,
 * deliberate limitation.</strong> When both limits are configured and the
 * first consume succeeds while a later one rejects, the earlier capacity has
 * already been consumed and is <em>not</em> returned. So a request rejected
 * on the minute limit may still have spent a unit of the day's allowance.
 * There is no rollback, compensation, or reservation here, and none is
 * invented: the counter is an atomic consume, not a transaction, and
 * compensating it would require an atomic multi-key operation the counter
 * contract does not define. Evaluating one limit before the other does not
 * remove the asymmetry either — which limit rejects first depends on runtime
 * counts, not on the configured values, so no ordering of the two limit
 * values can make this safe in general.
 *
 * <p>The consequence is deliberate and is why {@link
 * GatewayUsagePolicyEnforcementResult#rejectedWindow()} always names the
 * window that actually rejected: a caller can always tell that the request
 * was <em>not</em> admitted, while knowing a sibling limit may have been
 * charged. For a fixed-window allowance the inaccuracy is bounded — at most
 * one unit per window per rejected request — and it fails in the
 * conservative direction: capacity is over-counted, never under-counted, so
 * a configured limit still cannot be exceeded. Resolving the asymmetry
 * properly belongs to the later runtime-enforcement milestone.
 *
 * <p><strong>Nothing is consumed unless a limit can actually be checked.</strong>
 * An actor with no enabled policy, or a disabled policy, consumes nothing:
 * there is no limit to apply, and spending capacity for a request that no
 * limit governs would silently shrink the actor's real allowance. Ambiguous
 * configuration propagates {@link GatewayUsagePolicyAmbiguousException}
 * unchanged and consumes nothing, for the same reason.
 *
 * <p><strong>Failure is not rejection.</strong> If the counter cannot answer,
 * {@link GatewayUsagePolicyEnforcementException} propagates and the request is
 * neither admitted nor rejected. A counter that fails after an earlier
 * consume succeeded has the same asymmetry described above, and fails
 * closed: the request is not admitted.
 *
 * <p>Depends only on the resolver and the counter abstraction: no completion
 * service, no controller, no repository, no Redis, no provider, no PII
 * detector, no audit ledger, and no usage recorder. It is not wired into
 * live gateway traffic, so it currently has no effect on real requests.
 */
public class GatewayUsagePolicyEnforcementService {

    private final GatewayUsagePolicyResolver resolver;

    private final GatewayUsagePolicyCounter counter;

    /**
     * @param resolver supplies the actor's single effective policy; never null
     * @param counter performs the atomic admission; never null
     * @throws NullPointerException when either collaborator is null
     */
    public GatewayUsagePolicyEnforcementService(
            GatewayUsagePolicyResolver resolver, GatewayUsagePolicyCounter counter) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.counter = Objects.requireNonNull(counter, "counter must not be null");
    }
    /**
     * Resolves the actor's policy and consumes one request unit from every
     * configured request limit.
     *
     * @param actorSubject authenticated actor, never blank; trimmed exactly
     *        like the rest of the policy package trims it, so the resolver
     *        and the counter see the same value
     * @param now the instant the request is admitted at, never null
     * @return the enforcement result, never null
     * @throws IllegalArgumentException when {@code actorSubject} is blank or
     *         {@code now} is null
     * @throws GatewayUsagePolicyAmbiguousException when the actor has more
     *         than one enabled policy; propagated unchanged, nothing consumed
     * @throws GatewayUsagePolicyEnforcementException when a counter could not
     *         answer; the request is neither admitted nor rejected
     */
    public GatewayUsagePolicyEnforcementResult enforce(String actorSubject, Instant now) {
        String actor = requireActor(actorSubject);
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }

        GatewayUsagePolicyResolution resolution =
                Objects.requireNonNull(resolver.resolve(actor), "resolver must not return a null resolution");
        if (resolution instanceof GatewayUsagePolicyResolution.None) {
            // No policy governs this request, so there is no allowance to
            // spend. Consuming one anyway would silently shrink an allowance
            // no limit ever granted.
            return GatewayUsagePolicyEnforcementResult.noPolicy();
        }
        GatewayUsagePolicy policy = Objects.requireNonNull(
                resolution.effectivePolicy().orElse(null), "resolution must carry a policy");

        if (!policy.isEnabled()) {
            // A disabled policy is not applied, and applying nothing must not
            // cost capacity. The resolver only hands back enabled policies, so
            // this is a guard against a contract change, not a live path.
            return GatewayUsagePolicyEnforcementResult.inactive();
        }

        return consumeRequestLimits(actor, policy);
    }

    private GatewayUsagePolicyEnforcementResult consumeRequestLimits(String actor, GatewayUsagePolicy policy) {
        // Fixed order: day, then minute. Both configured limits must admit the
        // request; a null limit is unconstrained and is skipped, so an unset
        // limit never causes a counter call.
        GatewayUsagePolicyCounterWindow rejected = firstRejection(actor, policy);
        return rejected == null
                ? GatewayUsagePolicyEnforcementResult.allow()
                : GatewayUsagePolicyEnforcementResult.rejected(rejected);
    }

    private GatewayUsagePolicyCounterWindow firstRejection(String actor, GatewayUsagePolicy policy) {
        Long perDay = policy.getRequestsPerDay();
        if (perDay != null && !consume(actor, GatewayUsagePolicyCounterWindow.DAY, perDay)) {
            // Short-circuit: the minute limit is not touched once the day
            // limit has rejected.
            return GatewayUsagePolicyCounterWindow.DAY;
        }
        Long perMinute = policy.getRequestsPerMinute();
        if (perMinute != null && !consume(actor, GatewayUsagePolicyCounterWindow.MINUTE, perMinute)) {
            return GatewayUsagePolicyCounterWindow.MINUTE;
        }
        return null;
    }

    private boolean consume(String actor, GatewayUsagePolicyCounterWindow window, long limit) {
        try {
            return counter.tryConsume(actor, window, limit);
        } catch (GatewayUsagePolicyCounterUnavailableException ex) {
            // "Could not check" must never become REJECTED: the caller is
            // told the limit is unknown, not that it was exceeded.
            throw new GatewayUsagePolicyEnforcementException(ex);
        }
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
