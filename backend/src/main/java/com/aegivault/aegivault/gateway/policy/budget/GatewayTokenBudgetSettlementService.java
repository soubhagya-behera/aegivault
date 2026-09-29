package com.aegivault.aegivault.gateway.policy.budget;

import java.time.Instant;
import java.util.Objects;

/**
 * Settles a token reservation once the provider phase is over: it decides
 * whether the hold is replaced with real usage, released outright, or left in
 * place because usage could not be established.
 *
 * <p>This is the counterpart to {@link GatewayTokenBudgetEnforcementService},
 * which creates the reservation. That service decides whether capacity may be
 * held; this one decides what happens to the hold afterwards. Keeping them
 * apart matters because they run at different times — one before the provider
 * is invoked, one after — and because the answers are genuinely different: a
 * hold can be correct, can be given back entirely, or can only be left
 * standing.
 *
 * <p><strong>Three accounting truths, three behaviours.</strong>
 * <ol>
 *   <li><em>Known usage</em> — the provider reported an exact total, so the
 *       reservation is replaced by that figure. The total is used exactly as
 *       reported, whether it is lower than the reservation (unused capacity is
 *       returned) or higher (the over-reservation is recorded as real spend,
 *       never capped back down).</li>
 *   <li><em>No response</em> — the provider call failed before producing
 *       anything, so nothing was generated and nothing was consumed. The
 *       reservation is settled at zero. Holding it would permanently shrink
 *       the actor's day for a call that never reached a provider.</li>
 *   <li><em>Unknown usage</em> — a response exists but reported no token count.
 *       The reservation is <strong>left held</strong>, to expire with its UTC
 *       day.</li>
 * </ol>
 *
 * <p><strong>Why unknown usage is not treated as zero.</strong> A provider
 * response may well have consumed tokens, and nothing here can establish how
 * many. Releasing the hold would under-account for real spend and quietly widen
 * the day's allowance; inventing a number — from content length, byte size, or
 * any heuristic — would be a figure the system cannot stand behind, and a
 * silently wrong one would make the budget untrustworthy. So the reservation
 * simply stays held, which is the conservative direction: it may
 * <em>temporarily</em> reduce capacity the actor could otherwise have used, but
 * it never books spend that did not happen. The hold is not extended or
 * renewed — it simply rides out the same day-key TTL the reservation already
 * had, so no extra hold-forever mechanism exists and nothing accumulates
 * beyond that day.
 *
 * <p><strong>A blocked response is not a failed call.</strong> A response
 * rejected by security inspection was still produced, and may still have cost
 * tokens, so it is settled by usage exactly like a delivered one. Conflating a
 * block with a provider failure would release capacity that was genuinely
 * spent, which is the same under-accounting unknown usage avoids.
 *
 * <p><strong>Nothing is estimated and nothing is reserved here.</strong> This
 * service never creates a reservation; it only settles one that already
 * exists. It reads no prompt or response content, applies no character, byte,
 * or model heuristic, and defaults no token value.
 *
 * <p><strong>The window is derived from the caller's own instant.</strong> The
 * same UTC day arithmetic the reservation used is applied to the original
 * instant, so a settlement always lands on the day its reservation was taken
 * against — including one that happens after UTC midnight. {@code
 * Instant.now()} is never called internally.
 *
 * <p><strong>Failure is never reported as success.</strong> An unavailable
 * budget raises {@link GatewayTokenBudgetSettlementException} rather than
 * yielding a {@code RELEASED} or {@code RECONCILED} result that did not happen,
 * and nothing is retried, because a failed reconciliation has unknown server
 * state and a retry could settle the same tokens twice. A
 * {@link GatewayTokenBudgetReservationStateException} — an unknown or
 * already-settled reservation — propagates unchanged rather than being
 * swallowed as an unknown-usage outcome.
 *
 * <p>Depends only on {@link GatewayTokenBudget}. It holds no reference to the
 * completion service, the reservation coordinator, providers, Redis, the policy
 * repository, the rate limiter, the audit ledger, or any controller. Its one
 * live caller is {@link com.aegivault.aegivault.gateway.GatewayCompletionService},
 * which calls it once per provider phase — never before provider invocation and
 * never twice.
 */
public class GatewayTokenBudgetSettlementService {

    private final GatewayTokenBudget budget;

    /**
     * @param budget the atomic budget primitive used to reconcile, never null
     */
    public GatewayTokenBudgetSettlementService(GatewayTokenBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    /**
     * Settles the reservation held for one completed provider attempt.
     *
     * @param actorSubject verified JWT subject, never blank; trimmed exactly
     *        like the rest of the policy package trims it
     * @param reservedAt the instant the reservation was taken at, never null;
     *        the UTC day is derived from it and never from an internal clock
     * @param reservationId the reservation to settle, never blank
     * @param settlement what the provider phase produced, never null
     * @return the settlement outcome, never null
     * @throws IllegalArgumentException when the actor or reservation id is
     *         blank, or the settlement is internally inconsistent
     * @throws NullPointerException when the instant or settlement is null
     * @throws GatewayTokenBudgetReservationStateException when no such
     *         outstanding reservation exists; propagated unchanged
     * @throws GatewayTokenBudgetSettlementException when the budget could not
     *         be reached; the reservation is left unsettled
     */
    public GatewayTokenBudgetSettlementResult settle(
            String actorSubject,
            Instant reservedAt,
            String reservationId,
            GatewayTokenBudgetSettlement settlement) {
        String actor = requireActor(actorSubject);
        Objects.requireNonNull(reservedAt, "reservedAt must not be null");
        String id = requireReservationId(reservationId);
        Objects.requireNonNull(settlement, "settlement must not be null");

        // The very same fixed UTC calendar day arithmetic the reservation used,
        // applied to the original instant, so a late settlement still lands on
        // the day the hold belongs to.
        Instant dayStart = GatewayTokenBudgetWindow.DAY.windowStart(reservedAt);

        if (!settlement.hasResponse()) {
            // Nothing was generated, so nothing was consumed: give the capacity
            // back rather than shrinking the day for a call that never
            // reached a provider.
            reconcile(actor, dayStart, id, 0L);
            return GatewayTokenBudgetSettlementResult.released(id);
        }
        if (!settlement.hasKnownUsage()) {
            // A response exists but reported no count. Nothing here can
            // establish what it cost, and guessing would either under-account
            // or fabricate a figure, so the hold is left standing until its day
            // expires.
            return GatewayTokenBudgetSettlementResult.unknownUsage(id);
        }
        // The provider's own figure, used exactly as reported: never capped to
        // the reservation, so a genuine over-spend stays accounted.
        reconcile(actor, dayStart, id, settlement.totalTokens());
        return GatewayTokenBudgetSettlementResult.reconciled(id);
    }

    private void reconcile(String actor, Instant dayStart, String id, long actualTokens) {
        try {
            budget.reconcile(actor, dayStart, id, actualTokens);
        } catch (GatewayTokenBudgetUnavailableException ex) {
            // The settlement did not happen, so it is not reported as though it
            // had. No retry: a failed reconciliation leaves server state
            // unknown, and repeating it could settle the same tokens twice.
            throw new GatewayTokenBudgetSettlementException(ex);
        }
        // A GatewayTokenBudgetReservationStateException is deliberately not
        // caught: an unknown or already-settled reservation is a caller-state
        // failure, not an outage, and must reach the caller as itself.
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }

    private static String requireReservationId(String reservationId) {
        if (reservationId == null) {
            throw new NullPointerException("reservationId must not be null");
        }
        if (reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        return reservationId.trim();
    }
}