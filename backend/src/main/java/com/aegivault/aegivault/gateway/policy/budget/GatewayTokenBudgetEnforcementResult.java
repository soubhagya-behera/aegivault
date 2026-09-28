package com.aegivault.aegivault.gateway.policy.budget;

import java.util.Objects;

/**
 * The outcome of one token-budget admission attempt: whether this request may
 * hold its policy-configured slice of the actor's daily token budget.
 *
 * <p>There are exactly five states, and the three non-reserving ones are
 * deliberately distinct rather than folded into a single "nothing to do":
 * <ul>
 *   <li>{@link State#NO_POLICY} — the actor has no enabled policy, so nothing
 *       governs tokens for them;</li>
 *   <li>{@link State#INACTIVE} — a policy was resolved but is switched off;</li>
 *   <li>{@link State#NO_TOKEN_POLICY} — an active policy that declares no
 *       daily token limit at all;</li>
 *   <li>{@link State#RESERVED} — capacity was held successfully;</li>
 *   <li>{@link State#REJECTED} — the day's budget could not cover it.</li>
 * </ul>
 *
 * <p>The first three are the same decision the request-limit enforcement
 * service reaches for the same underlying causes, kept separate so a caller
 * can tell "no token rule applies" from "the token rule refused you". An
 * actor whose policy simply omits {@code tokensPerDay} must not be reported
 * as though their budget were exhausted.
 *
 * <p><strong>Only a reservation carries anything.</strong> A {@code RESERVED}
 * result exposes the {@code reservationId} and the {@code reservedTokens} that
 * one reservation holds — exactly the minimum a later step needs to reconcile
 * the hold against real provider usage. Every other state carries neither.
 *
 * <p><strong>What must never appear here.</strong> No actor subject, no
 * remaining or current budget, no limit, no day, no Redis key, and no internal
 * storage detail. A rejection in particular must not tell a caller how much
 * budget is left, so the only difference between the outcomes is whether
 * capacity was held.
 *
 * <p>Infrastructure failure is <em>not</em> a state: it raises
 * {@link GatewayTokenBudgetEnforcementException}, so "could not check" can
 * never be misread as "budget exceeded".
 *
 * @param state the outcome, never null
 * @param reservationId the id the hold is recorded under; non-null if and only
 *        if {@code state} is {@link State#RESERVED}
 * @param reservedTokens the amount this reservation holds, strictly positive;
 *        {@code 0} unless {@code state} is {@link State#RESERVED}
 * @param rejectionReason why a reservation was refused; non-null if and only if
 *        {@code state} is {@link State#REJECTED}
 * @throws IllegalArgumentException when the fields do not match the state
 */
public record GatewayTokenBudgetEnforcementResult(
        State state, String reservationId, long reservedTokens, RejectionReason rejectionReason) {

    /** The closed set of outcomes a token-budget attempt can have. */
    public enum State {
        /** No enabled policy governs this actor. */
        NO_POLICY,
        /** The resolved policy exists but is switched off. */
        INACTIVE,
        /** The policy is active but declares no daily token limit. */
        NO_TOKEN_POLICY,
        /** Capacity was held for this request. */
        RESERVED,
        /** The day's budget could not cover this request. */
        REJECTED
    }

    /**
     * The only reason a reservation is refused.
     *
     * <p>A closed enum rather than a free string so the reason cannot drift,
     * so no new value can be introduced without a deliberate decision, and so
     * nothing descriptive (an amount, a limit, an actor) can leak through it.
     */
    public enum RejectionReason {
        /** The actor's daily token capacity for this UTC day is spent. */
        TOKEN_BUDGET_EXCEEDED
    }

    public GatewayTokenBudgetEnforcementResult {
        Objects.requireNonNull(state, "state must not be null");
        switch (state) {
            case RESERVED -> {
                // A hold without an id could never be reconciled later, and a
                // non-positive amount would report a successful hold of
                // nothing.
                if (reservationId == null || reservationId.isBlank()) {
                    throw new IllegalArgumentException(
                            "reservationId must not be blank when the state is RESERVED");
                }
                if (reservedTokens <= 0L) {
                    throw new IllegalArgumentException(
                            "reservedTokens must be positive when the state is RESERVED");
                }
                if (rejectionReason != null) {
                    throw new IllegalArgumentException(
                            "rejectionReason must be absent when the state is RESERVED");
                }
            }
            case REJECTED -> {
                // A refusal holds nothing, so an id or an amount would imply
                // capacity was set aside when none was.
                if (reservationId != null) {
                    throw new IllegalArgumentException(
                            "reservationId must be absent unless the state is RESERVED");
                }
                if (reservedTokens != 0L) {
                    throw new IllegalArgumentException(
                            "reservedTokens must be zero unless the state is RESERVED");
                }
                if (rejectionReason == null) {
                    throw new IllegalArgumentException(
                            "rejectionReason is required when the state is REJECTED");
                }
            }
            default -> {
                // Nothing was held, so nothing may be reported as held, and
                // nothing was refused either.
                if (reservationId != null || reservedTokens != 0L || rejectionReason != null) {
                    throw new IllegalArgumentException(
                            "only RESERVED and REJECTED carry reservation detail");
                }
            }
        }
    }

    /** The actor has no enabled policy; nothing governs their tokens. */
    public static GatewayTokenBudgetEnforcementResult noPolicy() {
        return new GatewayTokenBudgetEnforcementResult(State.NO_POLICY, null, 0L, null);
    }

    /** The resolved policy is switched off, so no token limit is applied. */
    public static GatewayTokenBudgetEnforcementResult inactive() {
        return new GatewayTokenBudgetEnforcementResult(State.INACTIVE, null, 0L, null);
    }

    /** The policy is active but declares no daily token limit. */
    public static GatewayTokenBudgetEnforcementResult noTokenPolicy() {
        return new GatewayTokenBudgetEnforcementResult(State.NO_TOKEN_POLICY, null, 0L, null);
    }

    /**
     * Capacity was held for this request.
     *
     * @param reservationId the id the hold is recorded under, never blank
     * @param reservedTokens the amount held, strictly positive
     */
    public static GatewayTokenBudgetEnforcementResult reserved(
            String reservationId, long reservedTokens) {
        return new GatewayTokenBudgetEnforcementResult(
                State.RESERVED, reservationId, reservedTokens, null);
    }

    /**
     * The day's budget could not cover this request; nothing was held.
     *
     * @param reason why it was refused, never null
     */
    public static GatewayTokenBudgetEnforcementResult rejected(RejectionReason reason) {
        return new GatewayTokenBudgetEnforcementResult(
                State.REJECTED, null, 0L, Objects.requireNonNull(reason, "reason must not be null"));
    }

    /** Whether capacity was held for this request. */
    public boolean isReserved() {
        return state == State.RESERVED;
    }

    /**
     * Whether the request may proceed on token grounds.
     *
     * <p>True when capacity was held <em>and</em> when no token rule applies.
     * It is never true for a {@code REJECTED}, and it never means "nothing was
     * consulted" on its own — that is what separates it from plain admission.
     */
    public boolean admits() {
        return state != State.REJECTED;
    }
}