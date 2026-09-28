package com.aegivault.aegivault.gateway.policy.budget;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicy;
import java.time.Instant;

/**
 * Atomically reserves part of an actor's daily <em>token</em> budget for one
 * fixed UTC day.
 *
 * <p>This is the token-budget <em>reservation primitive</em>, not token-budget
 * enforcement and not policy interpretation. It knows an actor, a day, a limit,
 * and a requested amount — and nothing else. It never sees
 * {@link GatewayUsagePolicy}, never resolves one, and never decides which
 * policy applies or what a rejection should mean for a caller.
 *
 * <p><strong>Why a reservation, and not a check.</strong> Actual provider token
 * usage is known only <em>after</em> the provider responds, while admission
 * happens <em>before</em> the provider is invoked. A pre-request
 * "read the current total, then compare" is therefore unsafe for concurrent
 * requests: two requests can both read the same total, both conclude there is
 * room, and both proceed. Counting only settled usage would let an actor spend
 * an unlimited number of concurrent requests against a single day's allowance.
 * A reservation closes that gap by claiming capacity <em>before</em> the
 * provider is called, so the amount a request is admitted against already
 * includes every amount reserved before it — including reservations whose
 * requests are still in flight and have no usage yet.
 *
 * <p><strong>The reservation amount is supplied by the caller and is not
 * usage.</strong> {@code requestedTokens} is whatever the caller explicitly
 * asks to hold. This primitive never estimates it: there is no
 * character-to-token conversion, no response-size guess, no max-token
 * assumption, and no model-specific formula anywhere in this package. Deciding
 * what a future gateway request should reserve is a separate concern and is
 * deliberately not answered here.
 *
 * <p><strong>Semantics.</strong> For one actor's current UTC day the attempt
 * succeeds exactly when
 * <pre>
 *     usedTokens + reservedTokens + requestedTokens &lt;= limit
 * </pre>
 * holds, where {@code usedTokens} is settled provider usage, {@code
 * reservedTokens} is everything currently held by outstanding reservations, and
 * {@code limit} is the configured daily limit. A satisfied attempt adds
 * {@code requestedTokens} to the reserved total and returns
 * {@link GatewayTokenBudgetReservation.State#RESERVED}. An unsatisfied attempt
 * returns {@link GatewayTokenBudgetReservation.State#REJECTED} and changes
 * <em>nothing</em>: rejected attempts deliberately consume no capacity, so a
 * client hammering a spent budget cannot push the day's total further over.
 *
 * <p><strong>Atomicity is the whole point.</strong> Implementations must
 * perform the read, the capacity comparison, and the reservation as one
 * indivisible operation. Separate read-then-write steps are not an acceptable
 * approximation: they are exactly the race this abstraction exists to close.
 *
 * <p><strong>Not wired into gateway traffic.</strong> No gateway code path calls
 * this interface, so it has zero effect on real requests and {@code
 * tokensPerDay} remains unenforced. It is infrastructure for a later
 * milestone.
 *
 * <p><strong>What must not leak into this type.</strong> No HTTP status, no
 * controller, no audit entry, no provider, no policy resolution, and no token
 * estimation.
 *
 * <p><strong>Settling a reservation is the second half of the primitive.</strong>
 * The result of a successful reservation carries a {@code reservationId} and
 * the {@code reservedTokens} it holds, which is the minimum a later operation
 * needs to move a reservation from "reserved" to "actually used".
 * {@link #reconcile} is that operation: it consumes the reservation and
 * replaces the held amount with the tokens a provider actually reported, never
 * clamping an over-reservation to the amount that was held. No release, refund,
 * or expiry-based reclamation path exists beyond that, and nothing in this
 * package decides when a provider's usage should be reported.
 */
public interface GatewayTokenBudget {

    /**
     * Atomically reserves {@code requestedTokens} of {@code actorSubject}'s
     * daily token budget for the UTC day starting at {@code windowStart}.
     *
     * <p>The attempt succeeds exactly when
     * {@code usedTokens + reservedTokens + requestedTokens <= limit}, and a
     * successful attempt is all-or-nothing: the full amount is reserved, never
     * part of it. Concurrent attempts for the same actor and day are decided
     * against one indivisible state boundary, so simultaneous requests can
     * never overshoot the limit.
     *
     * <p>A rejected attempt reserves nothing and leaves the day's totals
     * exactly as they were.
     *
     * @param actorSubject verified JWT subject, never blank; trimmed exactly
     *        like the rest of the policy package trims it
     * @param windowStart the inclusive start of the UTC calendar day to
     *        reserve in, never null and never a partial day
     * @param limit the daily token limit, strictly positive
     * @param requestedTokens the amount to reserve, strictly positive; an
     *        amount the caller supplies explicitly, never an estimate made
     *        here
     * @return the attempt outcome, never null
     * @throws IllegalArgumentException when the actor is blank, the window
     *         start is not a UTC day start, the limit is not strictly
     *         positive, or the requested amount is not strictly positive
     * @throws GatewayTokenBudgetUnavailableException when the underlying store
     *         could not answer; an implementation that can fail must fail
     *         closed rather than reserve
     */
    GatewayTokenBudgetReservation tryReserve(
            String actorSubject, Instant windowStart, long limit, long requestedTokens);

    /**
     * Settles one outstanding reservation against the tokens a provider
     * actually reported, replacing held capacity with settled usage.
     *
     * <p><strong>Why this exists.</strong> A reservation is capacity held in
     * <em>anticipation</em> of usage, so it is an upper bound that is normally
     * wrong: a provider usually consumes fewer tokens than were held, and the
     * unused remainder must go back or the actor is charged for capacity they
     * never used. Provider usage is only known after the response, which is
     * exactly when this operation runs.
     *
     * <p><strong>Accounting.</strong> The reservation is removed and the day's
     * accounted total becomes
     * <pre>
     *     previousTotal - reservedTokens + actualTokens
     * </pre>
     * so reserving 100 and settling 80 leaves 80 accounted, settling 100 leaves
     * 100, and settling 20 releases 80. The removed reservation is what makes
     * this safe to run: the amount subtracted is the amount this reservation
     * actually held, never a caller-supplied figure that could be wrong.
     *
     * <p><strong>Actual usage is never clamped to the reservation.</strong> If
     * a provider reports more than was held, the extra is recorded as real
     * usage and the reconciliation still succeeds. A request can legitimately
     * overshoot a reservation — a longer prompt, a cached-miss, a model that
     * spends more than expected — and silently capping the figure at the
     * reservation would under-report what the actor was actually charged for.
     * The consequence is intentional and is a post-provider accounting fact, not
     * a reservation failure: the day's accounted total may exceed the configured
     * limit, and <em>every subsequent</em> {@code tryReserve} for that actor and
     * day is then rejected until the day rolls over. Overspending is therefore
     * not preventable by a pre-request check alone; it is detected and contained
     * afterwards. Reporting it any other way would hide it.
     *
     * <p><strong>Reservations are single-use.</strong> A successful
     * reconciliation consumes the reservation, so a replayed settlement fails
     * like any unknown id and can never double-count the same tokens.
     *
     * <p><strong>Reservations are actor-and-day scoped.</strong> An id is
     * reachable only through its own actor's budget for its own day, so one
     * actor cannot settle another's reservation even holding the id, and a
     * yesterday's reservation cannot be settled into today's budget.
     *
     * <p><strong>Atomicity is the whole point.</strong> Implementations must
     * locate the reservation, remove it, and adjust the accounted total as one
     * indivisible operation, so a concurrent reservation can never be decided
     * against a total mid-adjustment.
     *
     * <p><strong>Not wired into gateway traffic.</strong> No gateway code path
     * calls this interface, so nothing settles in production and
     * {@code tokensPerDay} remains unenforced.
     *
     * @param actorSubject verified JWT subject, never blank; trimmed exactly
     *        like the rest of the policy package trims it
     * @param windowStart the inclusive start of the UTC calendar day the
     *        reservation was made in, never null and never a partial day
     * @param reservationId the id returned by the
     *        {@link GatewayTokenBudgetReservation} being settled, never blank
     * @param actualTokens the tokens the provider actually reported, never
     *        negative; zero is a legitimate reported value, and a value above
     *        the reserved amount is recorded as-is rather than clamped
     * @return the reconciliation outcome, never null
     * @throws IllegalArgumentException when the actor is blank, the window
     *         start is not a UTC day start, the reservation id is blank, or the
     *         actual amount is negative
     * @throws NullPointerException when the window start or the reservation id
     *         is null
     * @throws GatewayTokenBudgetReservationStateException when no such
     *         outstanding reservation exists for this actor on this day,
     *         including an already-reconciled one
     * @throws GatewayTokenBudgetUnavailableException when the underlying store
     *         could not answer
     */
    GatewayTokenBudgetReconciliation reconcile(
            String actorSubject, Instant windowStart, String reservationId, long actualTokens);
}