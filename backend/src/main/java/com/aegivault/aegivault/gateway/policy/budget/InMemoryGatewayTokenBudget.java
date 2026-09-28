package com.aegivault.aegivault.gateway.policy.budget;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local, thread-safe implementation of {@link GatewayTokenBudget} for
 * deterministic local development and testing.
 *
 * <p><strong>One atomic state boundary per actor and day.</strong> Every
 * attempt runs inside exactly one
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
 * keyed by the actor and that day's start, and the read, the capacity
 * comparison, and the reservation all happen inside that single map operation.
 * No lock is taken and no check-then-act gap exists, so simultaneous
 * reservations for one actor and day can never overshoot the limit: at most
 * {@code limit} tokens' worth can be admitted. The implementation never issues
 * a second {@code compute} for one attempt.
 *
 * <p><strong>Deliberately separate from the request-rate counter.</strong>
 * This class shares no internals, no state, and no constants with
 * {@code InMemoryGatewayUsagePolicyCounter} or
 * {@code InMemoryGatewayRateLimiter} — not their maps, not their window
 * arithmetic. They answer different questions about different quantities: those
 * count requests, this holds tokens, and merging them would let a change to
 * request admission silently alter token accounting, and the reverse.
 *
 * <p><strong>Local only.</strong> This state lives in one JVM heap: it is not
 * shared between application instances, so a multi-instance deployment needs
 * {@link RedisGatewayTokenBudget} to hold a day's budget once across the fleet.
 * There is no scheduler, no Redis, and no persistence. Entries for superseded
 * days are dropped when the same actor moves to a newer day, so an idle actor's
 * budget does not linger.
 *
 * <p>Depends on nothing but a {@link Clock}: no controller, no completion
 * service, no policy resolution, no provider, no detectors, no repositories.
 */
public class InMemoryGatewayTokenBudget implements GatewayTokenBudget {

    private final Clock clock;

    /**
     * One entry per actor and day, holding that day's reserved reservations.
     *
     * <p>Keying by actor <em>and</em> day start is what makes the day boundary
     * explicit: a new day is a new key, so one day's reservations can never
     * bleed into the next day's budget and two actors can never share one.
     */
    private final ConcurrentHashMap<BudgetKey, DayBudget> budgets = new ConcurrentHashMap<>();

    /**
     * One actor's budget on one UTC day: the settled usage so far plus the
     * reservations still outstanding against it.
     *
     * <p>The two are tracked separately because they answer different
     * questions. {@code usedTokens} is settled, irreversible provider usage;
     * the {@code reservations} map is capacity still held in anticipation of
     * usage and which reconciliation may yet give back. The capacity check
     * sums both, so a settled request and an in-flight one both count against
     * the day, but only the second is reversible.
     *
     * <p>Each reservation is kept as its own entry rather than folded into a
     * bare total so reconciliation can find exactly what a given
     * {@code reservationId} holds and subtract that amount, instead of trusting
     * a caller-supplied figure. The map is replaced wholesale on every
     * reservation and on every reconciliation, so it is never mutated in place
     * and never observed half-updated.
     */
    private record DayBudget(long usedTokens, Map<String, Long> reservations) {

        private static final DayBudget EMPTY = new DayBudget(0L, Map.of());

        /**
         * Everything the day currently accounts for: settled usage plus every
         * outstanding reservation.
         *
         * <p>This is the {@code usedTokens + reservedTokens} term the
         * reservation boundary is specified in, kept as one total so the
         * capacity comparison reads exactly the documented expression.
         */
        long totalAccounted() {
            return usedTokens + reservations.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** The actor and UTC day one budget belongs to. */
    private record BudgetKey(String actorSubject, Instant dayStart) {}

    /** Production constructor using the system UTC clock. */
    public InMemoryGatewayTokenBudget() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock time source used for day bookkeeping, never null
     */
    public InMemoryGatewayTokenBudget(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public GatewayTokenBudgetReservation tryReserve(
            String actorSubject, Instant windowStart, long limit, long requestedTokens) {
        // Everything invalid is rejected before a single byte of state is read
        // or written: a programming error must never create a budget, reserve
        // capacity, or touch another actor's state.
        String actor = GatewayTokenBudgetValidation.requireReservable(
                actorSubject, windowStart, limit, requestedTokens);

        GatewayTokenBudgetReservation[] outcome = new GatewayTokenBudgetReservation[1];

        // The entire attempt — read the day's held reservations, compare, and
        // either reserve or leave everything untouched — happens inside this
        // one atomic map operation. ConcurrentHashMap.compute holds the bin
        // lock for this key, so two threads can never both read a total that
        // still has room and both reserve against it.
        budgets.compute(new BudgetKey(actor, windowStart), (key, existing) -> {
            DayBudget current = existing == null ? DayBudget.EMPTY : existing;
            if (current.totalAccounted() + requestedTokens > limit) {
                // Rejected before any write: a refused reservation costs the
                // actor nothing, so hammering a spent budget cannot push the
                // day's held total further over the limit.
                outcome[0] = GatewayTokenBudgetReservation.rejected();
                return current;
            }

            // The id is minted inside the atomic region, so a concurrent
            // attempt can never be handed an id that is already in use.
            String reservationId = UUID.randomUUID().toString();
            Map<String, Long> reservations = new LinkedHashMap<>(current.reservations());
            reservations.put(reservationId, requestedTokens);
            outcome[0] = GatewayTokenBudgetReservation.reserved(reservationId, requestedTokens);
            // Only the reservation map grows: settled usage is untouched by a
            // new attempt.
            return new DayBudget(current.usedTokens(), Map.copyOf(reservations));
        });

        evictSupersededDays(actor, windowStart);
        return outcome[0];
    }

    @Override
    public GatewayTokenBudgetReconciliation reconcile(
            String actorSubject, Instant windowStart, String reservationId, long actualTokens) {
        // Everything invalid is rejected before any state is read or written,
        // exactly as for a reservation: a programming error must never adjust a
        // budget, consume a reservation, or touch another actor's state.
        String actor = GatewayTokenBudgetValidation.requireReconcilable(
                actorSubject, windowStart, reservationId, actualTokens);
        String id = reservationId.trim();
        GatewayTokenBudgetReservationStateException[] failure =
                new GatewayTokenBudgetReservationStateException[1];

        // Lookup, removal, and the usage adjustment all happen inside this one
        // atomic map operation on the same actor-and-day boundary a reservation
        // uses. A concurrent reservation is therefore never decided against a
        // total midway through an adjustment: it sees either the state before
        // this reconciliation or the fully settled state after it.
        budgets.compute(new BudgetKey(actor, windowStart), (key, existing) -> {
            if (existing == null) {
                // No budget at all for this actor and day, so no such
                // reservation can exist.
                failure[0] = new GatewayTokenBudgetReservationStateException();
                return null;
            }
            Long held = existing.reservations().get(id);
            if (held == null) {
                // Unknown, already reconciled, or belonging to another actor or
                // day: all one indistinguishable failure, which is what stops a
                // caller from learning whether an id exists elsewhere.
                failure[0] = new GatewayTokenBudgetReservationStateException();
                return existing;
            }

            // The reservation is dropped and the actual amount takes its place
            // as settled usage. `held` is what this reservation actually holds,
            // never a caller-supplied figure, so the day cannot be credited
            // with a release it never made.
            Map<String, Long> reservations = new LinkedHashMap<>(existing.reservations());
            reservations.remove(id);
            // Over-reservation is not clamped and not rejected: the provider's
            // real figure is what the day now accounts for, even when that
            // pushes the day's total past the limit. Future reservations are
            // then rejected by the same capacity boundary as always, which is
            // the intended containment.
            return new DayBudget(existing.usedTokens() + actualTokens, Map.copyOf(reservations));
        });

        if (failure[0] != null) {
            throw failure[0];
        }
        return GatewayTokenBudgetReconciliation.reconciled();
    }

    /**
     * Drops this actor's budgets for days strictly older than the newest day
     * this actor is known to be using.
     *
     * <p>A new day is a new key, so without this an actor that keeps making
     * requests would accumulate one dead budget per day forever. Only strictly
     * older entries are dropped, which is what makes this safe to call right
     * after a reservation: the entry just written is never the one removed,
     * even when the caller deliberately reserved against a day other than today.
     * Only this actor's entries are touched; every other actor keeps its own.
     *
     * @param actorSubject the trimmed subject whose stale days are dropped
     * @param windowStart the day the just-made reservation belongs to
     */
    private void evictSupersededDays(String actorSubject, Instant windowStart) {
        // The clock matters only for an actor that has gone quiet since an
        // earlier day: a future caller resuming today must not be charged for,
        // or hold on to, what it reserved last week.
        Instant currentDay = GatewayTokenBudgetWindow.DAY.windowStart(clock.instant());
        Instant newestDay = windowStart.isAfter(currentDay) ? windowStart : currentDay;
        budgets.keySet().removeIf(key -> key.actorSubject().equals(actorSubject)
                && key.dayStart().isBefore(newestDay));
    }
}