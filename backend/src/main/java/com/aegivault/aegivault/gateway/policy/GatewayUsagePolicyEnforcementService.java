package com.aegivault.aegivault.gateway.policy;

import java.time.Instant;
import java.util.EnumMap;
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
 * <p><strong>All configured request limits must admit the request, as one
 * indivisible decision.</strong> The configured request limits are packed into
 * a single {@link GatewayUsagePolicyCounterRequest} and consumed with one
 * {@link GatewayUsagePolicyCounter#tryConsume(GatewayUsagePolicyCounterRequest)}
 * call, so the day and minute capacity are consumed together or not at all.
 * A null limit is unconstrained and is left out of the request entirely, so a
 * minute-only policy never touches the day counter and vice versa.
 *
 * <p><strong>Multi-limit consumption is atomic, not best-effort.</strong> An
 * earlier milestone consumed each window in its own call, which meant a
 * request rejected by the minute limit had already spent a unit of the day's
 * allowance. That partial-consumption case is gone: when any requested window
 * is exhausted, no window is incremented, so a refused request costs the actor
 * nothing. The result always names the window that actually rejected.
 *
 * <p>Nothing is consumed unless a limit can actually be checked. An actor with
 * no enabled policy, or a disabled policy, consumes nothing: there is no limit
 * to apply, and spending capacity for a request that no limit governs would
 * silently shrink the actor's real allowance. Ambiguous configuration
 * propagates {@link GatewayUsagePolicyAmbiguousException} unchanged and
 * consumes nothing, for the same reason. A policy that constrains only
 * {@code tokensPerDay} also consumes nothing, because this service does not
 * enforce tokens.
 *
 * <p><strong>Failure is not rejection.</strong> If the counter cannot answer,
 * {@link GatewayUsagePolicyEnforcementException} propagates and the request is
 * neither admitted nor rejected. A counter that fails part-way through a
 * multi-window attempt consumes nothing, since the counter's decision and
 * increments are one operation, and fails closed.
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

        return consumeRequestLimits(actor, policy, now);
    }

    private GatewayUsagePolicyEnforcementResult consumeRequestLimits(
            String actor, GatewayUsagePolicy policy, Instant now) {
        // One atomic request covering every configured request limit, never one
        // call per window: a day consume followed by a minute consume would
        // leave the day's capacity already spent when the minute limit rejects.
        EnumMap<GatewayUsagePolicyCounterWindow, Long> limits = new EnumMap<>(GatewayUsagePolicyCounterWindow.class);
        if (policy.getRequestsPerDay() != null) {
            limits.put(GatewayUsagePolicyCounterWindow.DAY, policy.getRequestsPerDay());
        }
        if (policy.getRequestsPerMinute() != null) {
            limits.put(GatewayUsagePolicyCounterWindow.MINUTE, policy.getRequestsPerMinute());
        }
        if (limits.isEmpty()) {
            // The policy constrains only tokens, which this service does not
            // enforce. No request capacity is consumed, and no counter call is
            // made to discover that.
            return GatewayUsagePolicyEnforcementResult.allow();
        }

        GatewayUsagePolicyCounterResult outcome = consume(
                new GatewayUsagePolicyCounterRequest(actor, now, limits));
        return outcome.isAllowed()
                ? GatewayUsagePolicyEnforcementResult.allow()
                : GatewayUsagePolicyEnforcementResult.rejected(outcome.rejectedWindow());
    }

    private GatewayUsagePolicyCounterResult consume(GatewayUsagePolicyCounterRequest request) {
        try {
            return counter.tryConsume(request);
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
