package com.aegivault.aegivault.gateway.policy.budget;

/**
 * The explicit input a future caller supplies to ask the token budget to hold a
 * specific number of tokens for one gateway request.
 *
 * <p><strong>This is the requested reservation amount, and nothing more.</strong>
 * The type carries exactly one value: how many tokens the caller wants held. It
 * is deliberately a separate, tiny contract rather than an extra argument on
 * {@link GatewayTokenBudget}, because deciding <em>what</em> to ask for is a
 * distinct decision from <em>asking</em>. This type fixes the shape of that
 * input; it does not answer it, and it does not feed anything.
 *
 * <p><strong>What {@code requestedTokens} is not.</strong> It is not actual
 * provider usage — that is only known after the provider responds, and it is
 * already accounted for separately by
 * {@link GatewayTokenBudget#reconcile(String, java.time.Instant, String, long)}.
 * It is not an estimate, not an inferred token count, not a response size, not a
 * character count, and not a byte count. Nothing in this class derives, infers,
 * converts, defaults, or approximates it, and no factory, helper, or fallback
 * anywhere computes one from another value. Whatever number arrives is the
 * caller's own figure, passed through unchanged.
 *
 * <p><strong>Deliberately no estimation exists here.</strong> There is no
 * tokenizer, no character-to-token or byte-to-token conversion, no
 * model-specific formula, no pricing or billing table, no max-token assumption,
 * and no heuristic of any kind. This is a design decision rather than a missing
 * feature: a fabricated token count would be a number the system cannot stand
 * behind, and a silently wrong one would make the budget itself untrustworthy,
 * so the amount stays caller-supplied and the question of what a real request
 * should reserve remains open until it can be answered honestly.
 *
 * <p><strong>Strictly positive, or nothing.</strong> A reservation of zero or
 * fewer tokens is a contradiction rather than a small reservation: it would
 * report a successful hold while holding nothing, and a negative amount would
 * hand capacity back to the day on every call. Both are refused at construction,
 * before the value is ever used, so an invalid amount can never reach a budget.
 * A boxed {@code Long} rather than a primitive {@code long} is used so that a
 * missing amount is a distinguishable {@code null} rejection rather than a
 * silent {@code 0}.
 *
 * <p><strong>Not connected to anything.</strong> This type is a standalone
 * contract. It is not a parameter of {@link GatewayTokenBudget}, no
 * implementation accepts it, and no gateway path constructs it: it holds no
 * reference to Redis, a database, Spring, a provider, a controller, or audit
 * code, and it performs no I/O. {@code tokensPerDay} therefore remains entirely
 * unenforced, and this class changes no runtime behaviour whatsoever.
 *
 * @param requestedTokens the caller-supplied reservation amount, never null and
 *        always strictly positive
 * @throws IllegalArgumentException when the amount is zero or negative
 * @throws NullPointerException when the amount is absent
 */
public record GatewayTokenBudgetReservationRequest(Long requestedTokens) {

    public GatewayTokenBudgetReservationRequest {
        if (requestedTokens == null) {
            // Distinguishable from 0 on purpose: "no amount was supplied" is a
            // different mistake from "zero was supplied", and neither is a
            // legitimate reservation.
            throw new NullPointerException("requestedTokens must not be null");
        }
        if (requestedTokens <= 0L) {
            // A non-positive amount is a contradiction, not a limit: nothing
            // could satisfy "hold nothing and report success", and a negative
            // figure would return capacity to the day on every call.
            throw new IllegalArgumentException("requestedTokens must be positive");
        }
    }

    /**
     * A reservation request for an explicit, caller-supplied amount.
     *
     * @param requestedTokens the number of tokens to hold, strictly positive
     * @return the validated request, never null
     * @throws IllegalArgumentException when the amount is zero or negative
     */
    public static GatewayTokenBudgetReservationRequest of(long requestedTokens) {
        return new GatewayTokenBudgetReservationRequest(requestedTokens);
    }
}