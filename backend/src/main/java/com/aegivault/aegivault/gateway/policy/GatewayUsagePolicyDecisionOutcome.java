package com.aegivault.aegivault.gateway.policy;

import java.util.List;
import java.util.Objects;

/**
 * The composed answer to "what is this actor's gateway usage policy decision
 * right now?": one resolution, one usage snapshot, and one evaluation,
 * reduced to a single flat value.
 *
 * <p>It exists because the pieces alone cannot answer that question. The
 * resolver knows only whether a policy exists, the snapshot provider knows
 * only what was used, and the evaluator knows only whether one policy is
 * satisfied by one snapshot. This type is the seam where the three meet, and
 * it exists so a caller can branch on one value.
 *
 * <p><strong>Five distinguishable states.</strong> {@code NO_POLICY} is
 * deliberately distinct from {@code ALLOW}: an actor with no enabled policy
 * is not an actor whose limits were checked and found satisfied, and turning
 * the first into the second would invent an enforcement answer that was never
 * computed. The other four states — {@code ALLOW}, {@code LIMIT_EXCEEDED},
 * {@code USAGE_UNKNOWN}, and {@code INACTIVE} — are exactly
 * {@link GatewayUsagePolicyDecision.State} and are produced by no logic here:
 * {@link #of(GatewayUsagePolicyDecision)} only renames the evaluator's state
 * and copies its already-computed violations.
 *
 * <p>Not every state carries the same information, and that is the point:
 * {@code NO_POLICY} has no violations and no token uncertainty because
 * nothing was ever evaluated.
 *
 * <p><strong>What it deliberately does not carry.</strong> There is no actor
 * subject, no policy owner, no policy label, no policy id, no raw usage row,
 * no provider content, no request or response content, no secret, and no PII.
 * The result identifies a decision state; it does not identify or describe the
 * actor, and it cannot be used to reconstruct one.
 *
 * <p><strong>This reports; it does not enforce.</strong> Nothing blocks a
 * request, grants a permission, reserves capacity, or writes a counter, and no
 * gateway traffic path consults it yet.
 *
 * @param state the composed outcome, never null
 * @param violations violated limits in
 *        {@link GatewayUsagePolicyViolation} declaration order; always empty
 *        for {@code NO_POLICY}
 * @param tokenUsageUnknown whether a token limit is configured while the token
 *        total is untrustworthy; always false for {@code NO_POLICY}
 * @throws NullPointerException when {@code state} is null
 * @throws IllegalArgumentException when {@code violations} is null
 */
public record GatewayUsagePolicyDecisionOutcome(
        State state, List<GatewayUsagePolicyViolation> violations, boolean tokenUsageUnknown) {

    /** Every state a composed decision can report. */
    public enum State {
        /**
         * The actor has no enabled policy, so nothing was evaluated.
         *
         * <p>A normal, non-error outcome that is not {@code ALLOW}: no limit
         * was checked, so no limit was satisfied.
         */
        NO_POLICY,
        /** Every configured limit is satisfied by the observed usage. */
        ALLOW,
        /** At least one configured limit is definitely exceeded. */
        LIMIT_EXCEEDED,
        /** The token limit's state cannot be established from the usage. */
        USAGE_UNKNOWN,
        /** The policy is disabled and was therefore not evaluated. */
        INACTIVE
    }

    public GatewayUsagePolicyDecisionOutcome {
        Objects.requireNonNull(state, "state must not be null");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
        if (state == State.NO_POLICY && (!violations.isEmpty() || tokenUsageUnknown)) {
            // Nothing was evaluated, so there is nothing to have violated and
            // no uncertainty to report. A state that mixes the two is
            // contradictory and is rejected here rather than downstream.
            throw new IllegalArgumentException("NO_POLICY must not report violations or unknown token usage");
        }
    }

    /**
     * The no-policy outcome: the actor has no enabled policy and nothing was
     * evaluated. Not an error, and never {@link State#ALLOW}.
     */
    public static GatewayUsagePolicyDecisionOutcome noPolicy() {
        return new GatewayUsagePolicyDecisionOutcome(State.NO_POLICY, List.of(), false);
    }

    /**
     * Projects one evaluator decision onto this outcome.
     *
     * <p>This is a renaming of an already-made decision, not a second
     * evaluation: every limit, every comparison, and the violation order were
     * decided by {@link GatewayUsagePolicyEvaluator} alone. The switch is
     * exhaustive over {@link GatewayUsagePolicyDecision.State}, so a future
     * evaluator state fails to compile here instead of being silently mapped
     * onto a wrong outcome.
     *
     * @param decision the evaluator's decision, never null
     */
    public static GatewayUsagePolicyDecisionOutcome of(GatewayUsagePolicyDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        return switch (decision.state()) {
            case ALLOW -> new GatewayUsagePolicyDecisionOutcome(
                    State.ALLOW, decision.violations(), decision.tokenUsageUnknown());
            case LIMIT_EXCEEDED -> new GatewayUsagePolicyDecisionOutcome(
                    State.LIMIT_EXCEEDED, decision.violations(), decision.tokenUsageUnknown());
            case USAGE_UNKNOWN -> new GatewayUsagePolicyDecisionOutcome(
                    State.USAGE_UNKNOWN, decision.violations(), decision.tokenUsageUnknown());
            case INACTIVE -> new GatewayUsagePolicyDecisionOutcome(
                    State.INACTIVE, decision.violations(), decision.tokenUsageUnknown());
        };
    }

    /** Whether a policy was resolved and evaluated. */
    public boolean isEvaluated() {
        return state != State.NO_POLICY;
    }

    /** Whether no enabled policy applied, so nothing was evaluated. */
    public boolean isNoPolicy() {
        return state == State.NO_POLICY;
    }
}