package com.aegivault.aegivault.gateway.policy.budget;

import java.util.Objects;

/**
 * The outcome of reconciling one reservation against actual provider usage.
 *
 * <p><strong>There is exactly one successful outcome.</strong> A reconciliation
 * either succeeds, or it fails with {@link GatewayTokenBudgetReservationStateException}
 * (no such outstanding reservation) or
 * {@link GatewayTokenBudgetUnavailableException} (the store could not answer).
 * There is no rejected state and no partial settlement, so this type carries no
 * accounting detail at all — a caller's only question is "did the reservation
 * move from reserved to actually used", and the answer is yes or it threw.
 *
 * <p><strong>It deliberately reports nothing about the budget.</strong> No
 * current total, no remaining capacity, no limit, no actor subject, no day, no
 * Redis key, and no state of the internal reservation map. A settled amount
 * would let a caller reconstruct the day's usage without a reporting path
 * having been designed for it, and a remaining-capacity figure would be a
 * capacity oracle for an actor who should only learn whether their own request
 * was accounted.
 *
 * <p><strong>Single-use.</strong> A successful reconciliation consumes the
 * reservation: the id is gone afterwards, and reconciling it again fails like
 * any unknown id. Nothing here can be replayed to settle the same tokens
 * twice.
 */
public record GatewayTokenBudgetReconciliation(State state) {

    /**
     * The states a reconciliation can report.
     *
     * <p>Only success is reportable. Every failure is an exception, which keeps
     * "the reservation did not exist", "the store is down", and "the
     * reservation was settled" as three distinguishable failures instead of
     * flattening them into one silent state.
     */
    public enum State {
        /**
         * The reservation was removed and the day's accounted total now carries
         * the actual provider usage in its place.
         */
        RECONCILED
    }

    public GatewayTokenBudgetReconciliation {
        Objects.requireNonNull(state, "state must not be null");
    }

    /**
     * The reservation was settled against actual usage.
     *
     * @return the successful reconciliation outcome
     */
    public static GatewayTokenBudgetReconciliation reconciled() {
        return new GatewayTokenBudgetReconciliation(State.RECONCILED);
    }

    /** Always true: any other outcome arrives as an exception. */
    public boolean isReconciled() {
        return state == State.RECONCILED;
    }
}