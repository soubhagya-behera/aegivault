package com.aegivault.aegivault.gateway.policy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 * no policy owner or label, no counter value, no Redis key or
 * connection detail, no database information, and no limit numbers. It says
 * which window decided the request and nothing about how much was counted.
 *
 * <p>It does carry the two facts the audit ledger needs in order to describe
 * the decision without re-deriving anything: {@code policyId}, the resolved
 * policy's own UUID (already a column of that table, so no new identifier is
 * introduced), and {@code enforcedWindows}, the request windows that were
 * actually checked. Neither is a usage count, a configured limit, a key, a
 * secret, or content.
 *
 * @param state the enforcement outcome, never null
 * @param rejectedWindow the window that rejected the request; non-null if and
 *        only if {@code state} is {@link State#REJECTED}
 * @param policyId the resolved policy's UUID; null only when no policy was
 *        resolved at all
 * @param enforcedWindows the request windows actually checked, in the fixed
 *        evaluation order; empty when no request limit applied
 * @throws IllegalArgumentException when the components do not match the state
 */
public record GatewayUsagePolicyEnforcementResult(
        State state,
        GatewayUsagePolicyCounterWindow rejectedWindow,
        java.util.UUID policyId,
        List<GatewayUsagePolicyCounterWindow> enforcedWindows) {

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
        enforcedWindows = List.copyOf(
                Objects.requireNonNull(enforcedWindows, "enforcedWindows must not be null"));
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
        return new GatewayUsagePolicyEnforcementResult(State.NO_POLICY, null, null, List.of());
    }

    /**
     * A disabled policy was resolved but not applied; nothing was consumed.
     *
     * @param policyId the resolved policy's UUID
     */
    public static GatewayUsagePolicyEnforcementResult inactive(java.util.UUID policyId) {
        return new GatewayUsagePolicyEnforcementResult(State.INACTIVE, null, policyId, List.of());
    }

    /**
     * Every configured request limit admitted the request.
     *
     * @param policyId the applied policy's UUID
     * @param enforcedWindows the windows that were checked, in evaluation order
     */
    public static GatewayUsagePolicyEnforcementResult allow(
            java.util.UUID policyId, List<GatewayUsagePolicyCounterWindow> enforcedWindows) {
        return new GatewayUsagePolicyEnforcementResult(
                State.ALLOW, null, policyId, enforcedWindows);
    }

    /**
     * A request limit was already spent; nothing was consumed.
     *
     * @param rejectedWindow the exhausted window
     * @param policyId the applied policy's UUID
     * @param enforcedWindows the windows that were checked, in evaluation order
     */
    public static GatewayUsagePolicyEnforcementResult rejected(
            GatewayUsagePolicyCounterWindow rejectedWindow,
            java.util.UUID policyId,
            List<GatewayUsagePolicyCounterWindow> enforcedWindows) {
        return new GatewayUsagePolicyEnforcementResult(
                State.REJECTED,
                Objects.requireNonNull(rejectedWindow, "window must not be null"),
                policyId,
                enforcedWindows);
    }

    /** Whether the request may proceed. */
    public boolean isAdmitted() {
        return state == State.ALLOW;
    }
}
