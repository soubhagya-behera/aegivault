package com.aegivault.aegivault.gateway.policy;

import java.util.List;
import java.util.Objects;

/**
 * The deterministic outcome of evaluating one policy against one usage
 * snapshot. It reports what was found and nothing more: it performs no
 * enforcement, blocks nothing, and grants no permission.
 *
 * <p>States:
 * <ul>
 *   <li>{@link State#ALLOW} — every configured limit is satisfied;</li>
 *   <li>{@link State#LIMIT_EXCEEDED} — at least one configured limit is
 *       definitely exceeded;</li>
 *   <li>{@link State#USAGE_UNKNOWN} — no limit is definitely exceeded, but a
 *       token limit is configured and the token total is untrustworthy, so
 *       the token limit's state cannot be established;</li>
 *   <li>{@link State#INACTIVE} — the policy is disabled, so it was not
 *       evaluated at all.</li>
 * </ul>
 *
 * <p>{@code USAGE_UNKNOWN} is deliberately not {@code LIMIT_EXCEEDED}: an
 * unknown total is not evidence of excess, and reporting it as a violation
 * would fail a request for the wrong reason. It is also not {@code ALLOW}:
 * the system does not have the information to say the token limit holds.
 *
 * <p>{@code INACTIVE} exists so a disabled policy is never silently treated
 * as satisfied. What an inactive policy should mean for a caller is a
 * separate decision, so this state carries no permission — it only says the
 * policy was not applied.
 *
 * <p>A definite violation outranks token uncertainty: if a request limit is
 * exceeded, the decision is {@code LIMIT_EXCEEDED} even when the token
 * total is unknown, and {@link #tokenUsageUnknown()} stays true so the
 * uncertainty is not lost.
 *
 * @param state the evaluation outcome, never null
 * @param violations the violated limits in {@link GatewayUsagePolicyViolation}
 *        declaration order; empty unless the state is
 *        {@code LIMIT_EXCEEDED}
 * @param tokenUsageUnknown whether a token limit is configured while the
 *        token total is untrustworthy
 */
public record GatewayUsagePolicyDecision(
        State state, List<GatewayUsagePolicyViolation> violations, boolean tokenUsageUnknown) {

    /** Deterministic evaluation outcomes. */
    public enum State {
        /** Every configured limit is satisfied by the snapshot. */
        ALLOW,
        /** At least one configured limit is definitely exceeded. */
        LIMIT_EXCEEDED,
        /** The token limit's state cannot be established from the snapshot. */
        USAGE_UNKNOWN,
        /** The policy is disabled and was therefore not evaluated. */
        INACTIVE
    }

    public GatewayUsagePolicyDecision {
        Objects.requireNonNull(state, "state must not be null");
        violations = List.copyOf(violations);
    }

    /** The all-clear: nothing exceeded, nothing unknown. */
    public static GatewayUsagePolicyDecision allow() {
        return new GatewayUsagePolicyDecision(State.ALLOW, List.of(), false);
    }

    /** The token limit could not be established. */
    public static GatewayUsagePolicyDecision usageUnknown() {
        return new GatewayUsagePolicyDecision(State.USAGE_UNKNOWN, List.of(), true);
    }

    /** The policy is disabled and was not evaluated. */
    public static GatewayUsagePolicyDecision inactive() {
        return new GatewayUsagePolicyDecision(State.INACTIVE, List.of(), false);
    }

    /**
     * At least one limit is definitely exceeded. {@code violations} must
     * already be in {@link GatewayUsagePolicyViolation} declaration order.
     */
    public static GatewayUsagePolicyDecision limitExceeded(
            List<GatewayUsagePolicyViolation> violations, boolean tokenUsageUnknown) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        return new GatewayUsagePolicyDecision(State.LIMIT_EXCEEDED, violations, tokenUsageUnknown);
    }

    /** Whether a limit is definitely exceeded. */
    public boolean isLimitExceeded() {
        return state == State.LIMIT_EXCEEDED;
    }

    /** Whether a specific limit was violated. */
    public boolean violates(GatewayUsagePolicyViolation violation) {
        return violations.contains(violation);
    }
}