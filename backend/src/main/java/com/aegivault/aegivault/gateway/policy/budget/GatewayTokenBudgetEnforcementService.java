package com.aegivault.aegivault.gateway.policy.budget;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicy;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyAmbiguousException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolution;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import java.time.Instant;
import java.util.Objects;

/**
 * Decides whether one gateway request may hold its configured slice of an
 * actor's daily token budget, by resolving the actor's policy and making a
 * single atomic reservation through {@link GatewayTokenBudget}.
 *
 * <p>This is the token half of policy enforcement, and it is deliberately a
 * sibling of {@code GatewayUsagePolicyEnforcementService} rather than an
 * extension of it: that service consumes <em>request</em> counts through
 * {@code GatewayUsagePolicyCounter}, this one reserves <em>tokens</em> through
 * {@code GatewayTokenBudget}. They answer different questions about different
 * quantities, consult different storage, and can fail independently — so they
 * are never merged and never compensate for one another.
 *
 * <p><strong>The reservation amount is configuration, never computed.</strong>
 * The amount held is exactly {@code policy.reservationTokensPerRequest}, the
 * owner-configured value, passed through unchanged. Nothing here estimates it:
 * there is no character-to-token conversion, no prompt-length measurement, no
 * response-size guess, no model name, and no max-token heuristic. That is what
 * makes the budget trustworthy — a fabricated token count would be a number
 * the system cannot stand behind. The policy's own cross-field invariant
 * guarantees a non-null, positive amount whenever {@code tokensPerDay} is set,
 * so no fallback default is needed or wanted here.
 *
 * <p><strong>Reservation is atomic, and all-or-nothing.</strong> The decision
 * and the hold happen in one {@code GatewayTokenBudget.tryReserve} call, so
 * the amount this request is admitted against already includes every amount
 * reserved before it, including reservations whose provider calls are still in
 * flight. A refused reservation consumes nothing, so hammering a spent budget
 * cannot push the day's total further over. Nothing is ever read in Java and
 * then compared separately: that read-then-write split is exactly the race the
 * budget primitive exists to close.
 *
 * <p><strong>The window is the current UTC day.</strong> The day start is
 * derived from the caller-supplied instant using the same fixed UTC
 * calendar-day arithmetic the request counter uses — never a rolling 24 hours
 * and never the JVM default zone — so a token budget and a request count for
 * the same actor on the same instant always describe the same day.
 *
 * <p><strong>Nothing is reserved unless a token limit actually applies.</strong>
 * An actor with no policy, a switched-off policy, or a policy that declares no
 * {@code tokensPerDay} gets a distinct non-reserving result and the budget is
 * never contacted at all: there is no allowance to spend, and spending one
 * would silently shrink an allowance no limit ever granted. Ambiguous
 * configuration propagates {@link GatewayUsagePolicyAmbiguousException}
 * unchanged and reserves nothing, because arbitrarily choosing between several
 * enabled policies could enforce the wrong limit.
 *
 * <p><strong>Failure is not rejection.</strong> If the budget cannot answer,
 * {@link GatewayTokenBudgetEnforcementException} propagates and the result is
 * neither reserved nor rejected. Reporting that as a rejection would tell the
 * caller their budget was exhausted when the truth is that no answer was
 * available, which would surface a wrong reason and mask an outage.
 *
 * <p><strong>Reconciliation is deliberately absent.</strong> This service
 * reserves and nothing more. It never calls {@code reconcile}, because the real
 * provider usage does not exist until the provider has responded, and a
 * reconciliation step belongs to the runtime path that will follow the provider
 * call. The returned reservation id is carried forward unchanged for that later
 * step to settle.
 *
 * <p>Depends only on the policy resolver and the token budget. It holds no
 * reference to the completion service, the request counter, the rate limiter,
 * Redis, providers, repositories, controllers, or the audit ledger. Its one
 * live caller is {@link com.aegivault.aegivault.gateway.GatewayCompletionService},
 * which calls it once per request, after request inspection and provider
 * selection and immediately before provider invocation.
 */
public class GatewayTokenBudgetEnforcementService {

    private final GatewayUsagePolicyResolver resolver;

    private final GatewayTokenBudget budget;

    /**
     * @param resolver supplies the actor's effective policy, never null
     * @param budget the atomic reservation primitive, never null
     */
    public GatewayTokenBudgetEnforcementService(
            GatewayUsagePolicyResolver resolver, GatewayTokenBudget budget) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    /**
     * Decides whether this request may reserve its configured daily token
     * capacity, and reserves it when it may.
     *
     * @param actorSubject verified JWT subject, never blank; trimmed exactly
     *        like the rest of the policy package trims it
     * @param now the instant the attempt happens at, never null; the UTC day is
     *        derived from it, never from an internal clock
     * @return the decision, never null
     * @throws IllegalArgumentException when the actor is blank
     * @throws NullPointerException when {@code now} is null
     * @throws GatewayUsagePolicyAmbiguousException when the actor has more than
     *         one enabled policy; nothing is reserved
     * @throws GatewayTokenBudgetEnforcementException when the budget could not
     *         be consulted; nothing is reserved
     */
    public GatewayTokenBudgetEnforcementResult reserve(
            String actorSubject, Instant now) {
        String actor = requireActor(actorSubject);
        Objects.requireNonNull(now, "now must not be null");

        GatewayUsagePolicyResolution resolution = Objects.requireNonNull(
                resolver.resolve(actor), "resolver must not return a null resolution");
        if (resolution instanceof GatewayUsagePolicyResolution.None) {
            // No policy governs this request, so there is no token allowance to
            // spend. Reserving anyway would silently shrink an allowance no
            // limit ever granted.
            return GatewayTokenBudgetEnforcementResult.noPolicy();
        }
        GatewayUsagePolicy policy = Objects.requireNonNull(
                resolution.effectivePolicy().orElse(null), "resolution must carry a policy");

        if (!policy.isEnabled()) {
            // The resolver only returns enabled policies, so this guards against
            // a contract change rather than a live path. A disabled policy is
            // not applied, and applying nothing costs no capacity.
            return GatewayTokenBudgetEnforcementResult.inactive();
        }

        Long limit = policy.getTokensPerDay();
        if (limit == null) {
            // The policy constrains requests but not tokens. The budget is not
            // contacted at all: there is no limit to enforce, and discovering
            // that must not itself reserve or read anything.
            return GatewayTokenBudgetEnforcementResult.noTokenPolicy();
        }

        // Guaranteed by the policy's own cross-field invariant whenever
        // tokensPerDay is set: a daily token policy cannot exist without a
        // positive, policy-owned pre-request amount.
        long requestedTokens = Objects.requireNonNull(
                policy.getReservationTokensPerRequest(),
                "policy invariant requires a reservation amount whenever tokensPerDay is set");

        return reserve(actor, now, limit, requestedTokens);
    }

    private GatewayTokenBudgetEnforcementResult reserve(
            String actor, Instant now, long limit, long requestedTokens) {
        // The same fixed UTC calendar day the request counter uses, derived
        // identically, so both controls agree on which day they are counting.
        Instant dayStart = GatewayTokenBudgetWindow.DAY.windowStart(now);

        final GatewayTokenBudgetReservation outcome;
        try {
            // One atomic call decides and holds; the amount is the policy's own,
            // never a computed or adjusted figure.
            outcome = budget.tryReserve(actor, dayStart, limit, requestedTokens);
        } catch (GatewayTokenBudgetUnavailableException ex) {
            // "Could not check" must never become REJECTED: the caller is told
            // the budget is unknown, not that it was exceeded.
            throw new GatewayTokenBudgetEnforcementException(ex);
        }
        return outcome.isReserved()
                ? GatewayTokenBudgetEnforcementResult.reserved(
                        outcome.reservationId(), outcome.reservedTokens())
                : GatewayTokenBudgetEnforcementResult.rejected(
                        GatewayTokenBudgetEnforcementResult.RejectionReason
                                .TOKEN_BUDGET_EXCEEDED);
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        // Trimmed exactly like the rest of the policy package, so an untrimmed
        // spelling of one actor cannot resolve, and be charged, as another.
        return actorSubject.trim();
    }
}