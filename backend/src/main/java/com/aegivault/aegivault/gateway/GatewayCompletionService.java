package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementResult;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementService;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementResult;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementService;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlement;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementService;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.usage.GatewayUsageOutcome;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application-layer seam for gateway completions: rate-limit the actor,
 * inspect the request, then forward only what inspection allowed, then inspect the provider
 * response before returning it. One call first spends one attempt from
 * {@link GatewayRateLimiter} for the JWT-derived actor — a rejected actor
 * fails immediately as {@link GatewayRateLimitExceededException} before
 * inspection, audit, provider selection, or provider invocation — then
 * inspects the request under the
 * fixed strict policy through {@link SecurityInspectionService}, records
 * the request outcome once through {@link GatewayAuditService}, and —
 * only on request ALLOW — resolves one {@link LlmProvider} through the
 * {@link LlmProviderSelector} abstraction and forwards model plus
 * content through the selected provider. A successful provider response is
 * inspected separately through {@link ProviderResponseInspectionService}
 * under the same strict policy: a clean response returns as ALLOW with
 * the provider completion, while a sensitive response returns as BLOCK
 * with safe reason codes and no provider payload. Every provider
 * invocation that returns a response is then recorded once through
 * {@link GatewayUsageRecorder} — {@code DELIVERED} for a clean response,
 * {@code SECURITY_BLOCKED} for a blocked one (the blocked call may still
 * have consumed provider tokens) — carrying gateway metadata plus the
 * exact provider-reported counts only.
 *
 * <p>Request inspection and provider-response inspection are two
 * separate security decisions with distinct result types
 * ({@link SecurityInspectionResult} vs
 * {@link ProviderResponseInspectionResult}); the response check never
 * reuses or mutates the request decision.
 *
 * <p>Two independent admission controls run before anything else, in this
 * fixed order. First the platform-wide {@link GatewayRateLimiter} — a
 * rejected actor fails as {@link GatewayRateLimitExceededException} without
 * any policy check. Then the actor's own persistent
 * {@link GatewayUsagePolicyEnforcementService} request limits — a rejected
 * actor fails as {@link GatewayUsagePolicyLimitExceededException}, and
 * `NO_POLICY` or `INACTIVE` simply continue. They are never merged and never
 * compensate for one another: a global rejection never spends policy capacity,
 * because the policy check does not run, and the two keep distinct messages so
 * a caller can tell which control stopped it. Only after both admit does
 * inspection, audit, provider selection, or provider invocation happen, so
 * either rejection leaves no inspection audit entry and no usage record.
 *
 * <p><strong>Token budgets are reservation and accounting, never
 * prediction.</strong> {@code tokensPerDay} is enforced here, but by
 * <em>reserving</em> a slice of the actor's day before the provider runs and
 * <em>settling</em> that hold afterwards — never by estimating what the request
 * will cost. The reserved amount is the policy's own
 * {@code reservationTokensPerRequest}, used unchanged; nothing here converts
 * characters to tokens, measures the prompt, guesses a response size, reads a
 * model name, or applies a max-token heuristic, and no token count is ever
 * invented when a provider reports none.
 *
 * <p><strong>Reservation sits between provider selection and provider
 * invocation.</strong> After both admission controls, request inspection, its
 * audit entry, and provider selection have all succeeded — and only then — the
 * actor's configured daily token capacity is reserved in one atomic attempt
 * through {@link GatewayTokenBudgetEnforcementService}. That position is
 * deliberate: a request blocked by request inspection, or a request whose
 * provider could not be selected, never reserves anything, so the actor's day
 * is not charged for work that could not happen. {@code NO_POLICY},
 * {@code INACTIVE}, and {@code NO_TOKEN_POLICY} all continue with nothing
 * reserved and nothing consulted. A {@code REJECTED} decision fails as
 * {@link GatewayTokenBudgetLimitExceededException} before the provider is
 * invoked, so it creates no usage record and no provider-response audit event,
 * and it exposes nothing about usage, remaining budget, the limit, the reserved
 * amount, or the reservation id. A budget that could not answer is not a
 * rejection: it propagates as
 * {@link com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementException}
 * and is never turned into a 429.
 *
 * <p><strong>Settlement closes the hold before the response is
 * finalized.</strong> Once a provider response exists it is settled through
 * {@link GatewayTokenBudgetSettlementService} before anything else looks at it:
 * a known provider total is reconciled exactly as reported, a response with no
 * reported total leaves the reservation held until its own UTC day expires
 * (never released to zero, never estimated), and a provider that produced
 * nothing settles at zero so the day is not shrunk by a call that never reached
 * a provider. A security BLOCK and an oversized response both settle exactly
 * like any other response, because in both cases the provider really did produce
 * one. A settlement that could not be finalised propagates as
 * {@link com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementException}
 * and fails the request closed: no provider content, no security BLOCK body, and
 * no normal completion is returned while the day's accounting is unknown, and
 * nothing is retried.
 *
 * <p>A provider failure — including a provider-selection failure —
 * surfaces as {@link GatewayProviderException}
 * with a generic message (cause retained for server logs): it is never
 * converted into an ALLOW/BLOCK verdict and never leaks exception text
 * or request content. An oversized provider response fails the same way
 * — never truncated, never partially inspected, never returned — before
 * response inspection runs. Response inspection runs only after a
 * successful provider response within the size bound exists — never on
 * the failure path and never on oversized output. The single
 * request-inspection audit entry already recorded is left as-is — never
 * duplicated. Provider responses are never logged or persisted.
 *
 * <p>No usage row is recorded when no provider response exists: a
 * request-side BLOCK, a rate-limit rejection, an oversized provider
 * response, and a provider (or provider-selection) failure all return or
 * fail without touching usage persistence. A usage-persistence failure
 * itself propagates as {@link com.aegivault.aegivault.gateway.usage.GatewayUsageException}
 * with a generic message instead of the ALLOW/BLOCK response.
 */
@Service
@RequiredArgsConstructor
public class GatewayCompletionService {

    /**
     * Maximum provider-response content in characters (64 KiB). The single
     * provider-output size bound: reuses the gateway input-size bound
     * ({@link GatewayInspectRequest#MAX_CONTENT_LENGTH}) rather than
     * redefining the number, so request and response share one boundary
     * concept. Enforced after the provider call and before response
     * inspection — oversized output fails safely instead of being
     * inspected, truncated, or returned.
     */
    public static final int MAX_PROVIDER_RESPONSE_LENGTH = GatewayInspectRequest.MAX_CONTENT_LENGTH;

    private final GatewayRateLimiter rateLimiter;

    private final GatewayUsagePolicyEnforcementService policyEnforcement;

    /**
     * The token half of policy enforcement: reserves the actor's configured
     * daily token capacity immediately before provider invocation. Kept as the
     * coordinator abstraction, never the {@code GatewayTokenBudget}, Redis, the
     * policy resolver, or a policy repository, so a change to how a budget is
     * stored can never reach this class.
     */
    private final GatewayTokenBudgetEnforcementService tokenBudgetEnforcement;

    /**
     * The counterpart that settles a reservation once the provider phase is
     * over. Deliberately a separate collaborator from the one that reserves,
     * because they run at different times and answer different questions.
     */
    private final GatewayTokenBudgetSettlementService tokenBudgetSettlement;

    private final SecurityInspectionService inspections;

    private final ProviderResponseInspectionService responseInspections;

    private final LlmProviderSelector selector;

    private final GatewayAuditService audit;

    private final GatewayUsageRecorder usage;

    /**
     * Applies the actor's enabled usage policy's request limits, after the
     * global rate limiter and before anything else happens.
     *
     * <p>{@code NO_POLICY} and {@code INACTIVE} both continue: a request with
     * no policy, or with a policy that is switched off, is simply not governed
     * by one, and an inactive policy is never silently treated as a satisfied
     * one. Neither creates an audit entry, because neither is a security
     * decision.
     *
     * <p>{@code REJECTED} is <em>audited before</em> it is thrown, so the
     * evidence that the request was refused exists even though the refusal
     * never reached a provider. Failing here — before inspection — is what
     * guarantees a policy-rejected request inspects nothing, audits nothing,
     * selects no provider, invokes none, and records no usage.
     *
     * <p>Ambiguous policy configuration and an unavailable policy counter
     * propagate unchanged: both are failures to decide rather than rejections,
     * so neither can be turned into a 429 here, and neither is audited as a
     * decision because no decision was made.
     */
    private void enforceUsagePolicy(GatewayInspectionRequest inspection) {
        GatewayUsagePolicyEnforcementResult decision =
                policyEnforcement.enforce(inspection.actorSubject(), Instant.now());
        switch (decision.state()) {
            case NO_POLICY, INACTIVE -> {
                // No event: nothing was applied, so there is no decision to
                // evidence. The inspection audit below is unaffected.
            }
            case ALLOW -> audit.recordUsagePolicy(inspection.actorSubject(), decision);
            case REJECTED -> {
                // Audit first. If the append fails, the 500 propagates and the
                // request never returns 429 — a refusal whose evidence was not
                // stored must not be reported as if it had been.
                audit.recordUsagePolicy(inspection.actorSubject(), decision);
                // The rejecting window is deliberately not surfaced: the
                // response names the policy limit, never the actor's counts.
                throw new GatewayUsagePolicyLimitExceededException();
            }
            default -> throw new IllegalStateException("Unhandled policy decision state.");
        }
    }

    /**
     * One outstanding token hold, or {@link Optional#empty()} when no token
     * rule applied to this request.
     *
     * <p>The reservation instant is carried alongside the id because the
     * settlement derives the same UTC day from it: passing the very instant the
     * reservation was taken at is what keeps a late settlement on the day the
     * hold belongs to, even across UTC midnight. Nothing else is kept — not the
     * reserved amount, not the limit, not any budget state — so a hold can
     * never carry information outward.
     *
     * @param reservedAt the instant the reservation was taken at, never null
     * @param reservationId the id the hold is recorded under, never blank
     */
    private record TokenReservation(Instant reservedAt, String reservationId) {}

    /**
     * Reserves the actor's configured slice of their daily token budget, after
     * both admission controls, request inspection, its audit entry, and
     * provider selection have all succeeded, and immediately before the
     * provider is invoked.
     *
     * <p>{@code NO_POLICY}, {@code INACTIVE}, and {@code NO_TOKEN_POLICY} all
     * return empty: there is no allowance to spend, and spending one would
     * silently shrink an allowance no limit ever granted. {@code RESERVED}
     * returns the hold. {@code REJECTED} throws, so no provider runs, no usage
     * is recorded, no provider-response audit entry is written, and nothing
     * about the budget's state escapes.
     *
     * <p>Ambiguous configuration and an unavailable budget propagate unchanged:
     * both are failures to decide rather than rejections, so neither becomes a
     * 429 here, and neither is audited because no decision was made.
     *
     * @param inspection the request being completed, never null
     * @param reservedAt the instant the reservation is taken at, never null
     * @return the hold, or empty when no token limit applies
     */
    private Optional<TokenReservation> reserveTokens(
            GatewayInspectionRequest inspection, Instant reservedAt) {
        GatewayTokenBudgetEnforcementResult decision =
                tokenBudgetEnforcement.reserve(inspection.actorSubject(), reservedAt);
        switch (decision.state()) {
            case NO_POLICY, INACTIVE, NO_TOKEN_POLICY -> {
                // Nothing was applied, so nothing is reserved and there is no
                // token decision to evidence. The inspection audit above and
                // the provider path below are unaffected.
                return Optional.empty();
            }
            case RESERVED -> {
                // Only the id is carried forward, exactly the minimum a later
                // settlement needs; the provider request itself is untouched.
                return Optional.of(new TokenReservation(reservedAt, decision.reservationId()));
            }
            case REJECTED -> {
                // The budget's state is deliberately not surfaced: the message
                // names the control, never the actor's counts, limit, reserved
                // amount, or reservation id.
                throw new GatewayTokenBudgetLimitExceededException();
            }
            default -> throw new IllegalStateException("Unhandled token-budget decision state.");
        }
    }

    /**
     * Settles a hold against what the provider phase actually produced.
     *
     * <p>Nothing happens when no hold was taken, so a request that reserved
     * nothing costs nothing. A {@link
     * com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementException}
     * propagates: the day could not be finalised, and reporting the response
     * anyway would claim an accounting state the system cannot stand behind.
     *
     * @param inspection the request being completed, never null
     * @param reservation the hold, or empty when none was taken
     * @param settlement what the provider phase produced, never null
     */
    private void settleTokens(
            GatewayInspectionRequest inspection,
            Optional<TokenReservation> reservation,
            GatewayTokenBudgetSettlement settlement) {
        reservation.ifPresent(hold -> tokenBudgetSettlement.settle(
                inspection.actorSubject(), hold.reservedAt(), hold.reservationId(), settlement));
    }

    /**
     * The settlement for a provider response that exists: the provider's own
     * reported total when it gave one, and unknown usage when it did not.
     *
     * <p>Unknown usage is never turned into zero and never estimated. The
     * settlement service leaves such a hold standing until its day expires,
     * which is the conservative direction.
     *
     * @param response a provider response that already exists, never null
     * @return the settlement describing it, never null
     */
    private static GatewayTokenBudgetSettlement settlementFor(LlmResponse response) {
        Long totalTokens = response.usage().totalTokens();
        return totalTokens == null
                ? GatewayTokenBudgetSettlement.unknownUsage()
                : GatewayTokenBudgetSettlement.withUsage(totalTokens);
    }

    /**
     * Settles a hold for a provider call that produced no response at all, and
     * fails closed if it cannot.
     *
     * <p>The hold is given back in full because nothing was generated and
     * nothing was consumed. If the release itself fails, the settlement failure
     * propagates and the original provider error is kept as a suppressed
     * exception for server logs: the day's accounting is unknown, so the request
     * must not be reported as an ordinary provider failure.
     *
     * @param inspection the request being completed, never null
     * @param reservation the hold to release, or empty when none was taken
     * @param providerFailure the failure being reported, never null
     */
    private void releaseUnproducedAttempt(
            GatewayInspectionRequest inspection,
            Optional<TokenReservation> reservation,
            RuntimeException providerFailure) {
        try {
            settleTokens(inspection, reservation, GatewayTokenBudgetSettlement.noResponse());
        } catch (RuntimeException settlementFailure) {
            settlementFailure.addSuppressed(providerFailure);
            throw settlementFailure;
        }
    }

    /**
     * Inspects one request and completes it when allowed.
     *
     * @param inspection inspection input with server-generated id and
     *        JWT-derived actor, never null
     * @return ALLOW with the clean provider completion, or BLOCK with safe
     *         reason codes and no provider payload (either the request was
     *         blocked before reaching the provider, or the provider
     *         response was blocked instead of being returned)
     */
    public GatewayCompleteResponse complete(GatewayInspectionRequest inspection) {
        if (!rateLimiter.tryAcquire(inspection.actorSubject())) {
            throw new GatewayRateLimitExceededException();
        }
        enforceUsagePolicy(inspection);
        SecurityInspectionResult requestDecision = inspections.inspect(inspection, GatewaySecurityPolicy.strict());
        audit.record(inspection, requestDecision);
        if (requestDecision.verdict() == SecurityVerdict.BLOCK) {
            return GatewayCompleteResponse.blocked(requestDecision);
        }
        final LlmProvider selected;
        try {
            selected = selector.select(inspection.model());
        } catch (RuntimeException ex) {
            // Selection fails before any reservation exists, so there is
            // nothing to settle and the actor's day is untouched.
            throw new GatewayProviderException("Unable to complete gateway request.", ex);
        }
        // One instant drives both halves of the token budget, so a reservation
        // and its later settlement always describe the same UTC day.
        Instant reservedAt = Instant.now();
        Optional<TokenReservation> reservation = reserveTokens(inspection, reservedAt);
        final LlmResponse completion;
        try {
            completion = selected.complete(new LlmRequest(inspection.model(), inspection.content()));
        } catch (RuntimeException ex) {
            // A call that produced nothing must give its capacity back, and a
            // failure to do so must not be reported as a provider failure.
            releaseUnproducedAttempt(inspection, reservation, ex);
            throw new GatewayProviderException("Unable to complete gateway request.", ex);
        }
        // A response exists from here on, so the hold is settled before the
        // response is size-checked, inspected, recorded, or returned — which is
        // also why a security BLOCK and an oversized response are accounted
        // for exactly like a delivered one.
        settleTokens(inspection, reservation, settlementFor(completion));
        if (completion.content().length() > MAX_PROVIDER_RESPONSE_LENGTH) {
            throw new GatewayProviderException(
                    "Unable to complete gateway request.",
                    new IllegalStateException("Provider response exceeded maximum length."));
        }
        ProviderResponseInspectionResult responseDecision =
                responseInspections.inspect(completion, GatewaySecurityPolicy.strict());
        if (responseDecision.verdict() == SecurityVerdict.BLOCK) {
            usage.record(inspection, completion, GatewayUsageOutcome.SECURITY_BLOCKED);
            return GatewayCompleteResponse.blocked(responseDecision);
        }
        usage.record(inspection, completion, GatewayUsageOutcome.DELIVERED);
        return GatewayCompleteResponse.allowed(completion);
    }
}
