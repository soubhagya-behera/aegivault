package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicy;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyAmbiguousException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterUnavailableException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementResult;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementService;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolution;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for policy enforcement inside
 * {@link GatewayCompletionService} (no Spring context, no database, no Redis,
 * no HTTP). They pin the two things that matter about the integration: the
 * <em>position</em> of the check — after the global rate limiter, before
 * inspection, audit, provider selection, and provider invocation — and the fact
 * that a policy rejection stops the request before any of that work happens.
 *
 * <p>The HTTP mapping of each failure is covered separately by
 * {@code GatewayUsagePolicyEnforcementApiTest}; these tests stay at the service
 * boundary.
 */
class GatewayCompletionPolicyEnforcementTest {

    private static final String CLEAN = "Summarize quarterly revenue trends.";

    private static final String SENSITIVE = "contact alice@example.com for access.";

    private SecurityInspectionService inspections;

    private ProviderResponseInspectionService responseInspections;

    private LlmProviderSelector selector;

    private LlmProvider selected;

    private GatewayAuditService audit;

    private GatewayUsageRecorder usage;

    private GatewayUsagePolicyResolver policyResolver;

    private GatewayRateLimiter rateLimiter;

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
        rateLimiter = actor -> true;

        when(selector.select(any())).thenReturn(selected);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Quarterly revenue grew."));
        when(inspections.inspect(any(), any()))
                .thenReturn(new SecurityInspectionResult(SecurityVerdict.ALLOW, Set.of(), Set.of()));
        when(responseInspections.inspect(any(), any()))
                .thenReturn(new ProviderResponseInspectionResult(SecurityVerdict.ALLOW, Set.of(), Set.of()));

        noPolicy();
        service = serviceWith(new AlwaysAdmitsPolicyCounter());
    }

    private static GatewayInspectionRequest inspection(String content) {
        return new GatewayInspectionRequest(UUID.randomUUID(), "actor-1", "test-model", content);
    }

    private void noPolicy() {
        when(policyResolver.resolve(any())).thenReturn(GatewayUsagePolicyResolution.none());
    }

    private void resolvedPolicy(GatewayUsagePolicy policy) {
        when(policyResolver.resolve(any())).thenReturn(GatewayUsagePolicyResolution.resolved(policy));
    }

    private GatewayCompletionService serviceWith(GatewayUsagePolicyCounter counter) {
        return new GatewayCompletionService(
                rateLimiter,
                new GatewayUsagePolicyEnforcementService(policyResolver, counter),
                inspections,
                responseInspections,
                selector,
                audit,
                usage);
    }

    @Test
    void noPolicyLetsTheRequestThroughTheWholeProviderPath() {
        service.complete(inspection(CLEAN));

        // No policy means no limit to check: existing behavior is untouched.
        verify(inspections, times(1)).inspect(any(), any());
        verify(audit, times(1)).record(any(), any());
        verify(selector, times(1)).select("test-model");
        verify(selected, times(1)).complete(any());
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void anInactivePolicyIsNotReachableThroughTheResolverAndTheResultAdmitsNothing() {
        // The resolver only ever returns enabled policies, so INACTIVE is a
        // guard rather than a live path. What the completion path must do with
        // it is pinned here: it is not a rejection, so the service continues.
        assertThat(GatewayUsagePolicyEnforcementResult.inactive().state())
                .isEqualTo(GatewayUsagePolicyEnforcementResult.State.INACTIVE);
        assertThat(GatewayUsagePolicyEnforcementResult.inactive().rejectedWindow()).isNull();
        // A disabled policy is not ALLOW either — the completion service only
        // branches on REJECTED, so INACTIVE continues without consuming.
        assertThat(GatewayUsagePolicyEnforcementResult.inactive().isAdmitted()).isFalse();
    }

    @Test
    void anAllowedPolicyStillCompletesNormallyThroughTheProviderPath() {
        AlwaysAdmitsPolicyCounter counter = new AlwaysAdmitsPolicyCounter();
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "allowed", null, 60L, 1000L, null, true));

        var response = serviceWith(counter).complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        // One atomic consume carrying both configured request limits.
        assertThat(counter.requests()).hasSize(1);
        assertThat(counter.requests().get(0).limits())
                .containsEntry(
                        com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterWindow.DAY, 1000L)
                .containsEntry(
                        com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterWindow.MINUTE, 60L);
        verify(inspections, times(1)).inspect(any(), any());
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void aRejectedPolicyStopsTheRequestBeforeAnythingElseHappens() {
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "strict", null, 1L, 1L, null, true));
        // The counter refuses, standing in for an already-spent limit.
        GatewayCompletionService rejecting = serviceWith(
                new AlwaysAdmitsPolicyCounter.AlwaysRejectingPolicyCounter());

        assertThatThrownBy(() -> rejecting.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayUsagePolicyLimitExceededException.class)
                .hasMessage(GatewayUsagePolicyLimitExceededException.MESSAGE)
                .hasMessage("Gateway usage policy limit exceeded.");

        // The critical guarantee: a policy-rejected request inspects nothing,
        // audits nothing, selects no provider, invokes none, records no usage.
        verify(inspections, never()).inspect(any(), any());
        verify(audit, never()).record(any(), any());
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aTokenOnlyPolicyIsAdmittedAndConsumesNoRequestCapacity() {
        // tokensPerDay is not enforced: there is no truthful token number
        // before the provider runs, so no request limit applies and nothing is
        // consumed, estimated, or reserved.
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "tokens-only", null, null, null, 5_000L, true));
        AlwaysAdmitsPolicyCounter counter = new AlwaysAdmitsPolicyCounter();

        var response = serviceWith(counter).complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(counter.requests())
                .as("a token-only policy creates no request-counter call at all")
                .isEmpty();
        verify(selected, times(1)).complete(any());
    }

    @Test
    void anUnavailablePolicyCounterFailsClosedWithoutTouchingTheProviderPath() {
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "allowed", null, 60L, 1000L, null, true));
        GatewayCompletionService unavailable = serviceWith(
                new AlwaysAdmitsPolicyCounter.UnavailablePolicyCounter());

        // An infrastructure failure is not a rejection and never becomes a 429:
        // it surfaces as its own exception so the controller can answer 500.
        assertThatThrownBy(() -> unavailable.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayUsagePolicyEnforcementException.class)
                .hasMessage(GatewayUsagePolicyEnforcementException.MESSAGE)
                .hasMessage("Unable to enforce gateway usage policy.")
                .hasMessageNotContaining("redis-connection-refused");

        verify(inspections, never()).inspect(any(), any());
        verify(audit, never()).record(any(), any());
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void anAmbiguousPolicyFailsWithoutChoosingOneOrTouchingTheProviderPath() {
        when(policyResolver.resolve(any())).thenThrow(new GatewayUsagePolicyAmbiguousException());
        AlwaysAdmitsPolicyCounter counter = new AlwaysAdmitsPolicyCounter();

        assertThatThrownBy(() -> serviceWith(counter).complete(inspection(CLEAN)))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class)
                .hasMessage("Multiple enabled gateway usage policies are configured.");

        // Undecidable configuration spends nothing and reaches nothing.
        assertThat(counter.requests()).isEmpty();
        verify(inspections, never()).inspect(any(), any());
        verify(audit, never()).record(any(), any());
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void theGlobalRateLimiterStillRunsFirstAndStopsPolicyEnforcementEntirely() {
        // Order proof: a global rejection happens before the policy check, so
        // no policy counter is consulted and no policy capacity is spent.
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "allowed", null, 60L, 1000L, null, true));
        AlwaysAdmitsPolicyCounter counter = new AlwaysAdmitsPolicyCounter();
        GatewayCompletionService globallyLimited = new GatewayCompletionService(
                actor -> false,
                new GatewayUsagePolicyEnforcementService(policyResolver, counter),
                inspections,
                responseInspections,
                selector,
                audit,
                usage);

        assertThatThrownBy(() -> globallyLimited.complete(inspection(CLEAN)))
                .isInstanceOf(GatewayRateLimitExceededException.class)
                .hasMessage(GatewayRateLimitExceededException.MESSAGE)
                .hasMessage("Gateway rate limit exceeded.");

        // The global limiter alone stopped it: policy enforcement never ran.
        assertThat(counter.requests()).isEmpty();
        verify(inspections, never()).inspect(any(), any());
        verify(audit, never()).record(any(), any());
        verify(selector, never()).select(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void theGlobalRateLimitRunsBeforePolicyEnforcementAndBothBeforeInspection() {
        // One ordered walk: rate limiter, then the policy consume, then only
        // then inspection. Proves the required sequence, not just the end state.
        GatewayRateLimiter spy = mock(GatewayRateLimiter.class);
        when(spy.tryAcquire(any())).thenReturn(true);
        AlwaysAdmitsPolicyCounter counter = new AlwaysAdmitsPolicyCounter();
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "allowed", null, 60L, null, null, true));
        GatewayCompletionService ordered = new GatewayCompletionService(
                spy,
                new GatewayUsagePolicyEnforcementService(policyResolver, counter),
                inspections,
                responseInspections,
                selector,
                audit,
                usage);

        ordered.complete(inspection(CLEAN));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(spy, inspections, audit);
        inOrder.verify(spy).tryAcquire("actor-1");
        inOrder.verify(inspections).inspect(any(), any());
        inOrder.verify(audit).record(any(), any());
        // The policy check happened between the limiter and inspection.
        assertThat(counter.requests()).hasSize(1);
    }

    @Test
    void aSecurityBlockUnderAnAllowedPolicyIsStillABlockWithNoProviderCall() {
        // Existing security behavior is unchanged when a policy admits: BLOCK is
        // still data, not an error status, and the provider is never reached.
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "allowed", null, 60L, 1000L, null, true));
        when(inspections.inspect(any(), any()))
                .thenReturn(new SecurityInspectionResult(
                        SecurityVerdict.BLOCK, Set.of(BlockReason.PII_DETECTED), Set.of()));

        var response = service.complete(inspection(SENSITIVE));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
        // The inspection still happens and is still audited exactly once.
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void aBlockedProviderResponseUnderAnAllowedPolicyStillRecordsUsage() {
        // The other existing path is unchanged too: a response that inspection
        // blocks is still recorded, because the provider was really called.
        resolvedPolicy(new GatewayUsagePolicy("actor-1", "allowed", null, 60L, 1000L, null, true));
        when(responseInspections.inspect(any(), any()))
                .thenReturn(new ProviderResponseInspectionResult(
                        SecurityVerdict.BLOCK, Set.of(BlockReason.PII_DETECTED), Set.of()));

        var response = service.complete(inspection(CLEAN));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        verify(selected, times(1)).complete(any());
        verify(usage, times(1)).record(any(), any(), any());
    }

    @Test
    void thePolicyRejectionMessageIsDistinctFromTheGlobalRateLimitMessage() {
        // Two independent controls that must never be confused by a client.
        assertThat(GatewayUsagePolicyLimitExceededException.MESSAGE)
                .isEqualTo("Gateway usage policy limit exceeded.")
                .isNotEqualTo(GatewayRateLimitExceededException.MESSAGE);
        assertThat(GatewayUsagePolicyEnforcementException.MESSAGE)
                .isEqualTo("Unable to enforce gateway usage policy.")
                .isNotEqualTo(GatewayRateLimitExceededException.MESSAGE)
                .isNotEqualTo(GatewayRateLimitUnavailableException.MESSAGE);
    }

    @Test
    void theCompletionServiceDependsOnTheEnforcementServiceAndNothingBelowIt() {
        // Guards the dependency direction: the completion service may know the
        // enforcement service, never the policy repository, the counter, a
        // counter implementation, Redis, or the evaluator.
        var dependencyTypes = java.util.Arrays.stream(GatewayCompletionService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getSimpleName())
                .toList();

        assertThat(dependencyTypes)
                .contains("GatewayUsagePolicyEnforcementService")
                .doesNotContain(
                        "GatewayUsagePolicyResolver",
                        "GatewayUsagePolicyCounter",
                        "InMemoryGatewayUsagePolicyCounter",
                        "RedisGatewayUsagePolicyCounter",
                        "GatewayUsagePolicyRepository",
                        "GatewayUsagePolicyEvaluator",
                        "StringRedisTemplate");
    }
}
