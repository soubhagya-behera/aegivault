package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicy;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterRequest;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementService;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolution;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementException;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementResult;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementService;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlement;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementException;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementResult;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementService;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.provider.LlmUsage;
import com.aegivault.aegivault.gateway.usage.GatewayUsageOutcome;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import com.aegivault.aegivault.pii.PiiType;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Pure unit tests for token-budget enforcement and settlement inside
 * {@link GatewayCompletionService} (no Spring context, no database, no Redis,
 * no HTTP, no network).
 *
 * <p>They pin the two things this integration is actually about. The
 * <em>position</em> of the work: reservation happens after request inspection
 * and after provider selection but before the provider is invoked, and
 * settlement happens after the provider answered but before the response is
 * inspected or recorded. And the <em>consequences</em> of each outcome: nothing
 * is reserved when no token rule applies, a refusal never reaches a provider, a
 * provider that produced nothing gives its capacity back, a provider that
 * produced something is settled by exactly what it reported, and a settlement
 * that cannot be finalised fails the request closed.
 *
 * <p>Both coordinators are mocked here, so these tests say nothing about how a
 * budget is stored; {@code GatewayTokenBudgetLiveAccountingTest} walks the
 * same flow over the real budget primitive instead.
 */
class GatewayCompletionTokenBudgetTest {

    private static final String ACTOR = "actor-1";

    private static final String CLEAN = "Summarize quarterly revenue trends.";

    private static final String SENSITIVE = "contact alice@example.com for access.";

    private static final String RESERVATION_ID = "reservation-7f3a";

    private SecurityInspectionService inspections;

    private ProviderResponseInspectionService responseInspections;

    private LlmProviderSelector selector;

    private LlmProvider selected;

    private GatewayAuditService audit;

    private GatewayUsageRecorder usage;

    private GatewayUsagePolicyResolver policyResolver;

    private GatewayTokenBudgetEnforcementService tokenEnforcement;

    private GatewayTokenBudgetSettlementService tokenSettlement;

    private GatewayCompletionService service;

    @BeforeEach
    void setUp() {
        inspections = mock(SecurityInspectionService.class);
        responseInspections = mock(ProviderResponseInspectionService.class);
        selector = mock(LlmProviderSelector.class);
        selected = mock(LlmProvider.class);
        audit = mock(GatewayAuditService.class);
        usage = mock(GatewayUsageRecorder.class);
        policyResolver = mock(GatewayUsagePolicyResolver.class);
        tokenEnforcement = mock(GatewayTokenBudgetEnforcementService.class);
        tokenSettlement = mock(GatewayTokenBudgetSettlementService.class);

        when(selector.select(any())).thenReturn(selected);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Revenue grew.", usage(120L)));
        when(inspections.inspect(any(), any()))
                .thenReturn(new SecurityInspectionResult(SecurityVerdict.ALLOW, Set.of(), Set.of()));
        when(responseInspections.inspect(any(), any()))
                .thenReturn(new ProviderResponseInspectionResult(SecurityVerdict.ALLOW, Set.of(), Set.of()));
        when(policyResolver.resolve(any())).thenReturn(GatewayUsagePolicyResolution.none());
        when(tokenEnforcement.reserve(anyString(), any()))
                .thenReturn(GatewayTokenBudgetEnforcementResult.reserved(RESERVATION_ID, 400L));
        when(tokenSettlement.settle(anyString(), any(), anyString(), any()))
                .thenReturn(GatewayTokenBudgetSettlementResult.reconciled(RESERVATION_ID));
        service = serviceWith(actor -> true, new AlwaysAdmitsPolicyCounter());
    }

    /** Provider-reported usage whose total is exactly the given figure. */
    private static LlmUsage usage(Long totalTokens) {
        return new LlmUsage(20L, totalTokens == null ? null : totalTokens - 20L, totalTokens);
    }

    private static GatewayInspectionRequest inspection(String content) {
        return new GatewayInspectionRequest(UUID.randomUUID(), ACTOR, "test-model", content);
    }

    private GatewayCompletionService serviceWith(
            GatewayRateLimiter limiter, GatewayUsagePolicyCounter counter) {
        return new GatewayCompletionService(
                limiter,
                new GatewayUsagePolicyEnforcementService(policyResolver, counter),
                tokenEnforcement,
                tokenSettlement,
                inspections,
                responseInspections,
                selector,
                audit,
                usage);
    }

    private void resolvedPolicy(GatewayUsagePolicy policy) {
        when(policyResolver.resolve(any())).thenReturn(GatewayUsagePolicyResolution.resolved(policy));
    }

    /** A daily token policy that declares no request limit at all. */
    private static GatewayUsagePolicy tokenOnlyPolicy() {
        return new GatewayUsagePolicy(ACTOR, "tokens-only", null, null, null, 5_000L, 400L, true);
    }

    /** A policy that constrains both requests and tokens. */
    private static GatewayUsagePolicy mixedPolicy() {
        return new GatewayUsagePolicy(ACTOR, "mixed", null, 60L, null, 5_000L, 400L, true);
    }

    /** The one settlement the flow performed, captured for direct assertion. */
    private GatewayTokenBudgetSettlement theSettlement() {
        ArgumentCaptor<GatewayTokenBudgetSettlement> captor =
                ArgumentCaptor.forClass(GatewayTokenBudgetSettlement.class);
        verify(tokenSettlement, times(1)).settle(eq(ACTOR), any(), eq(RESERVATION_ID), captor.capture());
        return captor.getValue();
    }

    @Test
    void theWholeFlowRunsInOneFixedOrder() {
        // The order is the contract. Nothing may be reserved before the request
        // is known to be allowed and a provider is known to exist, and nothing
        // may be inspected, recorded, or returned before the hold is closed.
        GatewayRateLimiter limiter = mock(GatewayRateLimiter.class);
        when(limiter.tryAcquire(ACTOR)).thenReturn(true);
        GatewayUsagePolicyCounter counter = mock(GatewayUsagePolicyCounter.class);
        when(counter.tryConsume(any(GatewayUsagePolicyCounterRequest.class)))
                .thenReturn(com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterResult.allowed());
        resolvedPolicy(mixedPolicy());

        serviceWith(limiter, counter).complete(inspection(CLEAN));

        InOrder order = org.mockito.Mockito.inOrder(
                limiter, counter, inspections, audit, selector, tokenEnforcement, selected,
                tokenSettlement, responseInspections, usage);
        order.verify(limiter).tryAcquire(ACTOR);
        order.verify(counter).tryConsume(any(GatewayUsagePolicyCounterRequest.class));
        order.verify(inspections).inspect(any(), any());
        order.verify(audit).record(any(), any());
        order.verify(selector).select("test-model");
        order.verify(tokenEnforcement).reserve(eq(ACTOR), any());
        order.verify(selected).complete(any());
        order.verify(tokenSettlement).settle(eq(ACTOR), any(), eq(RESERVATION_ID), any());
        order.verify(responseInspections).inspect(any(), any());
        order.verify(usage).record(any(), any(), any());
        // And nothing else ran in between: exactly one of each step, no more.
        order.verifyNoMoreInteractions();
    }

    @Test
    void aReservationCarriesOnlyTheIdForwardAndLeavesTheProviderRequestAlone() {
        service.complete(inspection(CLEAN));

        // Exactly the reservation id is retained, and the provider still sees
        // the same model-and-content request it always did: no token count is
        // added, changed, or smuggled into the call.
        verify(tokenEnforcement, times(1)).reserve(eq(ACTOR), any());
        ArgumentCaptor<LlmRequest> forwarded = ArgumentCaptor.forClass(LlmRequest.class);
        verify(selected, times(1)).complete(forwarded.capture());
        assertThat(forwarded.getValue()).isEqualTo(new LlmRequest("test-model", CLEAN));
    }

    @Test
    void noPolicyReservesNothingAndLeavesTheProviderPathUnchanged() {
        // NO_POLICY: nothing governs this actor's tokens, so there is no
        // allowance to spend and none is spent.
        when(tokenEnforcement.reserve(anyString(), any()))
                .thenReturn(GatewayTokenBudgetEnforcementResult.noPolicy());

        var response = service.complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        verifyNoInteractions(tokenSettlement);
        verify(selected, times(1)).complete(any());
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void anInactivePolicyReservesNothingAndLeavesTheProviderPathUnchanged() {
        when(tokenEnforcement.reserve(anyString(), any()))
                .thenReturn(GatewayTokenBudgetEnforcementResult.inactive());

        var response = service.complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        verifyNoInteractions(tokenSettlement);
        verify(selected, times(1)).complete(any());
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void aPolicyWithoutAnyTokenLimitReservesNothing() {
        // NO_TOKEN_POLICY: the policy constrains requests but not tokens, so an
        // actor must never be reported as if their budget were exhausted.
        when(tokenEnforcement.reserve(anyString(), any()))
                .thenReturn(GatewayTokenBudgetEnforcementResult.noTokenPolicy());

        var response = service.complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        verifyNoInteractions(tokenSettlement);
        verify(selected, times(1)).complete(any());
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void aRejectedTokenBudgetStopsTheRequestBeforeTheProviderRuns() {
        when(tokenEnforcement.reserve(anyString(), any()))
                .thenReturn(GatewayTokenBudgetEnforcementResult.rejected(
                        GatewayTokenBudgetEnforcementResult.RejectionReason.TOKEN_BUDGET_EXCEEDED));

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayTokenBudgetLimitExceededException.class)
                .hasMessage("Gateway token budget exceeded.");

        // No provider, no usage row, and no provider-response inspection: the
        // refusal happens before any of them could exist.
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
        verify(responseInspections, never()).inspect(any(), any());
        // Nothing was reserved, so there is nothing to settle.
        verifyNoInteractions(tokenSettlement);
        // The request inspection that already happened is untouched: no second
        // inspection, and its one audit entry stands.
        verify(inspections, times(1)).inspect(any(), any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void aTokenBudgetRejectionMessageNamesOnlyTheControl() {
        // The body must not let a caller learn anything about the budget.
        assertThat(GatewayTokenBudgetLimitExceededException.MESSAGE)
                .isEqualTo("Gateway token budget exceeded.")
                .isNotEqualTo(GatewayUsagePolicyLimitExceededException.MESSAGE)
                .isNotEqualTo(GatewayRateLimitExceededException.MESSAGE)
                .doesNotContain("5000", "400", "reservation", "token-budget", "actor-1");
    }

    @Test
    void anUnavailableTokenBudgetFailsClosedAndIsNeverTurnedIntoA429() {
        when(tokenEnforcement.reserve(anyString(), any()))
                .thenThrow(new GatewayTokenBudgetEnforcementException(
                        new RuntimeException("redis-connection-refused-9z")));

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayTokenBudgetEnforcementException.class)
                .isNotInstanceOf(GatewayTokenBudgetLimitExceededException.class)
                .hasMessage("Unable to enforce gateway token budget.")
                .hasMessageNotContaining("redis-connection-refused");

        // "Could not check" is never let through as if it were "admitted", and
        // it never becomes a rejection either.
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
        verifyNoInteractions(tokenSettlement);
        // Only the steps that had already completed leave their marks.
        verify(inspections, times(1)).inspect(any(), any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void aProviderSelectionFailureReservesNothingAndStillFailsGenerically() {
        when(selector.select(any())).thenThrow(new RuntimeException("simulated-routing-boom-7q"));

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.");

        // Selection precedes the reservation precisely so that a request that
        // could not even pick a provider never holds the actor's tokens.
        verify(tokenEnforcement, never()).reserve(anyString(), any());
        verifyNoInteractions(tokenSettlement);
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aKnownProviderTotalIsSettledExactlyAsReported() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Revenue grew.", usage(3_750L)));

        var response = service.complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        // Exactly the provider's own figure, used as reported: not the reserved
        // 400, not a rounded or derived number, and not a cap.
        GatewayTokenBudgetSettlement settlement = theSettlement();
        assertThat(settlement.hasResponse()).isTrue();
        assertThat(settlement.totalTokens()).isEqualTo(3_750L);
    }

    @Test
    void anUnknownProviderTotalIsSettledAsUnknownUsageAndNoCountIsInvented() {
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Revenue grew."));

        var response = service.complete(inspection(CLEAN));

        // The normal provider flow continues, and the settlement says plainly
        // that nothing could be established: no zero, no estimate.
        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        GatewayTokenBudgetSettlement settlement = theSettlement();
        assertThat(settlement.hasResponse()).isTrue();
        assertThat(settlement.hasKnownUsage()).isFalse();
        assertThat(settlement.totalTokens()).isNull();
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void aProviderFailureReleasesTheReservationAndKeepsTheExistingProviderError() {
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.");

        // No response means nothing was generated, so the whole hold is given
        // back rather than shrinking the actor's day.
        GatewayTokenBudgetSettlement settlement = theSettlement();
        assertThat(settlement.hasResponse()).isFalse();
        assertThat(settlement.totalTokens()).isNull();
        verify(usage, never()).record(any(), any(), any());
        verify(responseInspections, never()).inspect(any(), any());
    }

    @Test
    void aFailedReleaseDuringAProviderFailureFailsClosedAndLeaksNothing() {
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));
        doThrow(new GatewayTokenBudgetSettlementException(new RuntimeException("redis-down-4k")))
                .when(tokenSettlement).settle(anyString(), any(), anyString(), any());

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayTokenBudgetSettlementException.class)
                .isNotInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to settle gateway token budget.")
                .hasMessageNotContaining("simulated-provider-boom")
                .hasMessageNotContaining("redis-down");

        // The provider error is kept for server logs only, never returned.
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aBlockedProviderResponseIsSettledFirstAndStillReturnsTheExistingBlock() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Email alice@example.com.", usage(90L)));
        when(responseInspections.inspect(any(), any()))
                .thenReturn(new ProviderResponseInspectionResult(
                        SecurityVerdict.BLOCK, Set.of(BlockReason.PII_DETECTED), Set.of(PiiType.EMAIL)));

        var response = service.complete(inspection(CLEAN));

        // A blocked response is a response: the provider really ran and may
        // really have spent tokens, so it is settled exactly like any other.
        assertThat(theSettlement().totalTokens()).isEqualTo(90L);
        // And the existing security outcome is untouched: HTTP-200 BLOCK data,
        // same reasons, same detected types, no provider payload.
        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        assertThat(response.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        verify(usage, times(1)).record(any(), any(), eq(GatewayUsageOutcome.SECURITY_BLOCKED));
    }

    @Test
    void aBlockedProviderResponseWithUnknownUsageKeepsTheReservationHeld() {
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Email alice@example.com."));
        when(responseInspections.inspect(any(), any()))
                .thenReturn(new ProviderResponseInspectionResult(
                        SecurityVerdict.BLOCK, Set.of(BlockReason.PII_DETECTED), Set.of(PiiType.EMAIL)));

        var response = service.complete(inspection(CLEAN));

        // Unknown usage behaves here exactly as it does for a delivered
        // response: left held, never released and never invented.
        assertThat(theSettlement().hasKnownUsage()).isFalse();
        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        verify(usage, times(1)).record(any(), any(), eq(GatewayUsageOutcome.SECURITY_BLOCKED));
    }

    @Test
    void anOversizedProviderResponseIsSettledAsAResponseThenFailsGenerically() {
        String oversized = "a".repeat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH + 1);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", oversized, usage(140L)));

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.");

        // The provider did produce a response, so this is not NO_RESPONSE: the
        // hold is reconciled with the real count even though the output is
        // refused, and the existing generic error is preserved.
        GatewayTokenBudgetSettlement settlement = theSettlement();
        assertThat(settlement.hasResponse()).isTrue();
        assertThat(settlement.totalTokens()).isEqualTo(140L);
        verify(responseInspections, never()).inspect(any(), any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void anOversizedProviderResponseWithUnknownUsageLeavesTheHoldHeld() {
        when(selected.complete(any())).thenReturn(new LlmResponse(
                "test-model", "a".repeat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH + 1)));

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayProviderException.class);

        GatewayTokenBudgetSettlement settlement = theSettlement();
        assertThat(settlement.hasResponse()).isTrue();
        assertThat(settlement.hasKnownUsage()).isFalse();
    }

    @Test
    void aFailedSettlementAfterAResponseFailsClosedAndReturnsNoContentAtAll() {
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Revenue grew.", usage(120L)));
        doThrow(new GatewayTokenBudgetSettlementException(new RuntimeException("redis-down-4k")))
                .when(tokenSettlement).settle(anyString(), any(), anyString(), any());

        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayTokenBudgetSettlementException.class)
                .hasMessage("Unable to settle gateway token budget.");

        // The day could not be finalised, so nothing about the response is
        // returned, inspected, or recorded, and nothing is retried.
        verify(responseInspections, never()).inspect(any(), any());
        verify(usage, never()).record(any(), any(), any());
        verify(tokenSettlement, times(1)).settle(anyString(), any(), anyString(), any());
    }

    @Test
    void aFailedSettlementAfterABlockedResponseHidesTheBlockBodyToo() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Email alice@example.com.", usage(90L)));
        when(responseInspections.inspect(any(), any()))
                .thenReturn(new ProviderResponseInspectionResult(
                        SecurityVerdict.BLOCK, Set.of(BlockReason.PII_DETECTED), Set.of(PiiType.EMAIL)));
        doThrow(new GatewayTokenBudgetSettlementException(new RuntimeException("redis-down-4k")))
                .when(tokenSettlement).settle(anyString(), any(), anyString(), any());

        // A security BLOCK is a real answer to a real request; returning it while
        // the budget is unaccounted for would report a success that did not
        // happen.
        assertThatThrownBy(() -> service.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayTokenBudgetSettlementException.class);
        verify(responseInspections, never()).inspect(any(), any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aRejectedRequestPolicyNeverReachesTheTokenBudget() {
        // Two independent controls, and the request one runs first: a
        // request-limited actor is refused before any token capacity is even
        // considered, so a request-policy refusal can never spend tokens.
        resolvedPolicy(new GatewayUsagePolicy(ACTOR, "one-per-minute", null, 1L, null, 5_000L, 400L, true));
        GatewayCompletionService limited = serviceWith(
                actor -> true, new AlwaysAdmitsPolicyCounter.AlwaysRejectingPolicyCounter());

        assertThatThrownBy(() -> limited.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayUsagePolicyLimitExceededException.class);

        verify(tokenEnforcement, never()).reserve(anyString(), any());
        verifyNoInteractions(tokenSettlement);
        verify(inspections, never()).inspect(any(), any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aGlobalRateLimitRejectionNeverReachesPolicyOrTokenEnforcement() {
        // The global limiter is first, so it stops the request before either
        // policy control has spent anything.
        GatewayCompletionService limited = serviceWith(actor -> false, new AlwaysAdmitsPolicyCounter());

        assertThatThrownBy(() -> limited.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayRateLimitExceededException.class);

        verify(tokenEnforcement, never()).reserve(anyString(), any());
        verifyNoInteractions(tokenSettlement);
        verify(inspections, never()).inspect(any(), any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aSecurityRequestBlockNeverAttemptsAReservation() {
        // The reason reservation sits after inspection: a request that never
        // reaches a provider must never hold the actor's tokens.
        when(inspections.inspect(any(), any()))
                .thenReturn(new SecurityInspectionResult(
                        SecurityVerdict.BLOCK, Set.of(BlockReason.PII_DETECTED), Set.of(PiiType.EMAIL)));

        var response = service.complete(inspection(SENSITIVE));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        verify(tokenEnforcement, never()).reserve(anyString(), any());
        verifyNoInteractions(tokenSettlement);
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
        // The inspection still happened and was still audited exactly once.
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void theCompletionServiceDependsOnTheTwoCoordinatorsAndNothingBelowThem() {
        // Guards the dependency direction: the completion service may know the
        // two coordinators and never the budget primitive, Redis, the policy
        // resolver or repository, or any budget implementation.
        var dependencyTypes = Arrays.stream(GatewayCompletionService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getSimpleName())
                .toList();

        assertThat(dependencyTypes)
                .contains("GatewayTokenBudgetEnforcementService", "GatewayTokenBudgetSettlementService")
                .doesNotContain(
                        "GatewayTokenBudget",
                        "InMemoryGatewayTokenBudget",
                        "RedisGatewayTokenBudget",
                        "GatewayUsagePolicyResolver",
                        "GatewayUsagePolicyRepository",
                        "StringRedisTemplate");
    }
}
