package com.aegivault.aegivault.gateway.policy.budget;

import java.util.Objects;

/**
 * The outcome of settling one token reservation after the provider phase.
 *
 * <p>Three states, one per accounting truth:
 * <ul>
 *   <li>{@link State#RECONCILED} — the provider reported an exact total, and
 *       the reservation was replaced with that real usage;</li>
 *   <li>{@link State#RELEASED} — no provider response existed, so the
 *       reservation was settled at zero and its capacity returned;</li>
 *   <li>{@link State#UNKNOWN_USAGE} — a response existed but reported no token
 *       count, so the reservation is <em>still held</em> and will expire with
 *       its day.</li>
 * </ul>
 *
 * <p>Infrastructure failure is <em>not</em> a state: it raises
 * {@link GatewayTokenBudgetSettlementException}, so "could not finalise" can
 * never be misread as "settled".
 *
 * <p><strong>What must never appear here.</strong> No actor subject, no policy
 * id, no Redis key, no current or remaining budget, no provider content, and no
 * counters. The only identity carried is the reservation id itself, which is
 * what a caller needs in order to correlate the settlement with the hold it
 * made — nothing about the actor, the day, or the amounts involved.
 *
 * @param state the settlement outcome, never null
 * @param reservationId the id of the reservation this settlement concerns,
 *        never blank
 * @throws IllegalArgumentException when the id is blank
 */
public record GatewayTokenBudgetSettlementResult(
        State state, String reservationId) {

    /** The closed set of settlement outcomes. */
    public enum State {
        /** The reservation was replaced with the provider's exact usage. */
        RECONCILED,
        /** No provider response existed; the reservation was settled at zero. */
        RELEASED,
        /**
         * Usage could not be established, so the reservation remains held until
         * its UTC day expires.
         */
        UNKNOWN_USAGE
    }

    public GatewayTokenBudgetSettlementResult {
        Objects.requireNonNull(state, "state must not be null");
        if (reservationId == null || reservationId.isBlank()) {
            // Every state is about one specific hold, so an unidentifiable
            // settlement could not be correlated with anything.
            throw new IllegalArgumentException("reservationId must not be blank");
        }
    }

    /**
     * The reservation was replaced with the provider's exact usage.
     *
     * @param reservationId the settled reservation, never blank
     */
    public static GatewayTokenBudgetSettlementResult reconciled(String reservationId) {
        return new GatewayTokenBudgetSettlementResult(State.RECONCILED, reservationId);
    }

    /**
     * The reservation was settled at zero because no response was produced.
     *
     * @param reservationId the released reservation, never blank
     */
    public static GatewayTokenBudgetSettlementResult released(String reservationId) {
        return new GatewayTokenBudgetSettlementResult(State.RELEASED, reservationId);
    }

    /**
     * Usage was unknown, so the reservation is still held.
     *
     * @param reservationId the still-held reservation, never blank
     */
    public static GatewayTokenBudgetSettlementResult unknownUsage(String reservationId) {
        return new GatewayTokenBudgetSettlementResult(State.UNKNOWN_USAGE, reservationId);
    }
}