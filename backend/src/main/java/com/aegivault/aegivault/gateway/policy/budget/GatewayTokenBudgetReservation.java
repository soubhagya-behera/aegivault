package com.aegivault.aegivault.gateway.policy.budget;

import java.util.Objects;

/**
 * The outcome of one atomic daily token-budget reservation attempt: either the
 * requested amount was fully reserved, or it was rejected and nothing changed.
 *
 * <p>The two states are exhaustive and there is deliberately no third
 * "unknown" state. When the underlying store could not answer, the attempt
 * fails with {@link GatewayTokenBudgetUnavailableException} instead of
 * producing one of these values, so a caller can never read an infrastructure
 * failure as a rejection or as a reservation.
 *
 * <p><strong>Adoption is all-or-nothing.</strong> A {@code RESERVED} result
 * means the whole amount is held against the day's limit; a partial amount is
 * never reserved.
 *
 * <p><strong>The minimum reconciliation contract, and nothing more.</strong> A
 * successful result carries the {@code reservationId} under which the amount
 * is held and the {@code reservedTokens} held, which is what a later operation
 * will need to settle a reservation against real provider usage. Settling,
 * releasing, and refunding are <em>not</em> implemented here, and nothing in
 * this record presumes how a caller will obtain the actual usage.
 *
 * <p><strong>What must never appear here.</strong> No current token count, no
 * remaining or available tokens, no limit, no window, no Redis key, no actor
 * subject, and no database or storage detail. A rejection in particular must
 * not tell a caller how much budget is left, so the only difference between the
 * two states is whether a reservation was made.
 *
 * @param state the outcome, never null
 * @param reservationId the identifier the reservation is held under;
 *        non-null if and only if {@code state} is {@link State#RESERVED}
 * @param reservedTokens the amount held by this reservation, strictly
 *        positive; {@code 0} if and only if {@code state} is
 *        {@link State#REJECTED}
 * @throws IllegalArgumentException when the fields do not match the state
 */
public record GatewayTokenBudgetReservation(
        State state, String reservationId, long reservedTokens) {

    /** The only two outcomes a reservation attempt can have. */
    public enum State {
        /** The full requested amount was reserved against the day's limit. */
        RESERVED,
        /** The day's budget could not cover the request; nothing was reserved. */
        REJECTED
    }

    public GatewayTokenBudgetReservation {
        Objects.requireNonNull(state, "state must not be null");
        if (state == State.RESERVED) {
            // A reservation without an id could never be settled later, and a
            // non-positive amount would be a reservation of nothing while still
            // reporting success.
            if (reservationId == null || reservationId.isBlank()) {
                throw new IllegalArgumentException(
                        "reservationId must not be blank when the state is RESERVED");
            }
            if (reservedTokens <= 0L) {
                throw new IllegalArgumentException(
                        "reservedTokens must be positive when the state is RESERVED");
            }
        } else {
            // A rejected attempt reserves nothing, so exposing an id or an
            // amount would imply capacity was held when none was.
            if (reservationId != null) {
                throw new IllegalArgumentException(
                        "reservationId must be absent when the state is REJECTED");
            }
            if (reservedTokens != 0L) {
                throw new IllegalArgumentException(
                        "reservedTokens must be zero when the state is REJECTED");
            }
        }
    }

    /**
     * The full amount was reserved.
     *
     * @param reservationId the identifier the amount is held under, never blank
     * @param reservedTokens the amount held, strictly positive
     */
    public static GatewayTokenBudgetReservation reserved(String reservationId, long reservedTokens) {
        return new GatewayTokenBudgetReservation(State.RESERVED, reservationId, reservedTokens);
    }

    /** Nothing was reserved; the day's budget could not cover the request. */
    public static GatewayTokenBudgetReservation rejected() {
        return new GatewayTokenBudgetReservation(State.REJECTED, null, 0L);
    }

    /** Whether the amount was reserved. */
    public boolean isReserved() {
        return state == State.RESERVED;
    }
}