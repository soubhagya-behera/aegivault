package com.aegivault.aegivault.gateway.policy;

import java.util.Objects;

/**
 * The immutable result of enforcing one actor's gateway usage policy request
 * limits for one request.
 *
 * <p>Four distinguishable states:
 * <ul>
 *   <li>{@link State#NO_POLICY} — the actor has no enabled policy, so no
 *       limit applied and nothing was consumed. A normal, non-error outcome
 *       that is deliberately <em>not</em> {@code ALLOW}: no limit was
 *       checked, so no limit was satisfied, and a caller that needs to tell
 *       those apart still can;</li>
 *   <li>{@link State#ALLOW} — every configured request limit admitted this
 *       request;</li>
 *   <li>{@link State#REJECTED} — a request limit was already spent, and
 *       {@link #rejectedWindow()} names which one;</li>
 *   <li>{@link State#INACTIVE} — a policy was resolved but is disabled, so it
 *       was not applied and nothing was consumed.</li>
 * </ul>
 *
 * <p><strong>"Could not check" is not a state here.</strong> When the counter
 * cannot answer, this object is never produced at all: the call fails with
 * {@link GatewayUsagePolicyEnforcementException}. An infrastructure failure
 * must never be laundered into a {@code REJECTED}, which would tell the
 * caller a limit was exceeded when in fact nothing was known.
 *
 * <p><strong>What it deliberately does not carry.</strong> No actor subject,
 * no policy owner, id, or label, no counter value, no Redis key or
 * connection detail, no database information, and no limit numbers. It says
 * which window decided the request and nothing about who asked or how much
 * was counted.
 *
 * @param state the enforcement outcome, never null
 * @param rejectedWindow the window that rejected the request; non-null if and
 *        only if {@code state} is {@link State#REJECTED}
 * @throws NullPointerException when {@code state} is null
 * @throws IllegalArgumentException when {@code rejectedWindow} does not match
 *         {@code state}
 */
public record GatewayUsagePolicyEnforcementResult(State state, GatewayUsagePolicyCounterWindow rejectedWindow) {

    /** Every outcome enforcing a policy's request limits can report. */
    public enum State {
        /** No enabled policy applied, so nothing was consumed. */
        NO_POLICY,
        /** Every configured request limit admitted the request. */
        ALLOW,
        /** A request limit was already spent; see {@code rejectedWindow}. */
        REJECTED,
        /** A policy was resolved but is disabled and was not applied. */
        INACTIVE
    }

    public GatewayUsagePolicyEnforcementResult {
        Objects.requireNonNull(state, "state must not be null");
        if ((state == State.REJECTED) != (rejectedWindow != null)) {
            // A REJECTED without a window cannot be acted on or explained, and
            // a window on a non-rejection would imply a limit was consulted
            // when none was. Neither is a state this type should be able to
            // represent.
            throw new IllegalArgumentException(
                    "rejectedWindow must be present exactly when the state is REJECTED");
        }
    }

    /** No policy applied; nothing was consumed. Not the same as {@link State#ALLOW}. */
    public static GatewayUsagePolicyEnforcementResult noPolicy() {
        return new GatewayUsagePolicyEnforcementResult(State.NO_POLICY, null);
    }

    /** Every configured request limit admitted the request. */
    public static GatewayUsagePolicyEnforcementResult allow() {
        return new GatewayUsagePolicyEnforcementResult(State.ALLOW, null);
    }

    /** A policy was resolved but is disabled, so nothing was consumed. */
    public static GatewayUsagePolicyEnforcementResult inactive() {
        return new GatewayUsagePolicyEnforcementResult(State.INACTIVE, null);
    }

    /**
     * A request limit was already spent.
     *
     * @param window the window that rejected, never null
     */
    public static GatewayUsagePolicyEnforcementResult rejected(GatewayUsagePolicyCounterWindow window) {
        return new GatewayUsagePolicyEnforcementResult(State.REJECTED, Objects.requireNonNull(window, "window must not be null"));
    }

    /** Whether the request may proceed. */
    public boolean isAdmitted() {
        return state == State.ALLOW;
    }
}
