package com.aegivault.aegivault.gateway.policy.budget;

/**
 * Signals that a token reservation could not be settled — the underlying
 * {@link GatewayTokenBudget} was unavailable or failed while reconciling.
 *
 * <p><strong>Fail closed, and stay honest about the reservation.</strong> A
 * settlement that could not be finalised is not a settlement: the reservation
 * remains held, and the caller is told that plainly rather than being handed a
 * {@code RELEASED} or {@code RECONCILED} result that never happened. Reporting
 * success here would leave the day's accounting silently wrong while claiming
 * it was correct, which is the one outcome worse than a visible failure.
 *
 * <p>It is raised only for <em>infrastructure</em> failure. A
 * {@link GatewayTokenBudgetReservationStateException} — an unknown or
 * already-settled reservation — propagates unchanged, because that is a caller
 * state error rather than an outage, and collapsing the two would hide which
 * one occurred.
 *
 * <p>No retry is attempted here: a failed reconciliation has unknown server
 * state, so retrying could settle the same tokens twice.
 *
 * <p>The message is fixed and safe: it names no Redis host, port, key,
 * reservation id, actor subject, token count, or underlying exception text. The
 * cause is retained for server logs only. No HTTP status is chosen here — this
 * coordinator is not yet in the request path and has no controller.
 */
public class GatewayTokenBudgetSettlementException extends RuntimeException {

    /** The only safe settlement-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to settle gateway token budget.";

    public GatewayTokenBudgetSettlementException(Throwable cause) {
        super(MESSAGE, cause);
    }
}