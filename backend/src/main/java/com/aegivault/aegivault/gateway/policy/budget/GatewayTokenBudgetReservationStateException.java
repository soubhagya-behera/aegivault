package com.aegivault.aegivault.gateway.policy.budget;

/**
 * Signals that a reconciliation named a reservation that could not be settled.
 *
 * <p>This is a <strong>budget-state</strong> failure, deliberately distinct from
 * {@link GatewayTokenBudgetUnavailableException}, which is an
 * <em>infrastructure</em> failure. The two must not be conflated: an outage says
 * "the accounting is unavailable", while this says "the accounting is
 * perfectly available and there is nothing to settle". A caller that retries on
 * one should not retry on the other, and an operator reading a log must be able
 * to tell a broken store from a bad reservation.
 *
 * <p><strong>One type, several indistinguishable causes.</strong> All of these
 * raise it, and none is named in the message:
 *
 * <ul>
 *   <li>the id was never issued by this budget;
 *   <li>it was already reconciled, so it is spent — reservations are
 *       single-use and a replay must not settle the same tokens twice;
 *   <li>it belongs to a different actor;
 *   <li>it belongs to a different UTC day.
 * </ul>
 *
 * <p>The last two matter most for isolation. A reservation is reachable only
 * through its own actor-and-day budget, so one actor cannot settle another's
 * reservation even if it somehow obtains the id. Collapsing these into one
 * indistinguishable failure is what makes that safe: reporting "unknown
 * reservation" for a wrong actor or a wrong day gives away nothing about which
 * part was wrong, and a probe cannot be used to confirm that an id exists
 * somewhere in the system.
 *
 * <p>The message is fixed and safe: no reservation id, actor subject, day, key,
 * token count, limit, or store detail. The cause, when there is one, is kept for
 * server logs only.
 */
public class GatewayTokenBudgetReservationStateException extends RuntimeException {

    /** The only safe reservation-state message, shared by every throw site. */
    public static final String MESSAGE = "Unable to reconcile gateway token reservation.";

    public GatewayTokenBudgetReservationStateException() {
        super(MESSAGE);
    }

    public GatewayTokenBudgetReservationStateException(Throwable cause) {
        super(MESSAGE, cause);
    }
}