package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicy;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementService;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolution;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudget;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementService;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetReservation;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementService;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetWindow;
import com.aegivault.aegivault.gateway.policy.budget.InMemoryGatewayTokenBudget;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.provider.LlmUsage;
import com.aegivault.aegivault.gateway.usage.GatewayUsageOutcome;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The live token-budget path over the <em>real</em> budget primitive: the real
 * {@link GatewayTokenBudgetEnforcementService} and
 * {@link GatewayTokenBudgetSettlementService} over a real
 * {@link InMemoryGatewayTokenBudget}, driven through the real
 * {@link GatewayCompletionService} (no Spring context, no database, no Redis,
 * no HTTP, no network).
 *
 * <p>Where {@code GatewayCompletionTokenBudgetTest} mocks both coordinators to
 * pin <em>where</em> the calls happen, this class lets the whole chain run so
 * the accounting itself is proven: known provider usage replaces the hold with
 * exactly the reported figure, a failed provider call gives the hold back, and
 * unknown usage leaves it standing. A token-only policy — no request limit at
 * all — is enforced end to end here, which is what makes {@code tokensPerDay}
 * a live control rather than a stored number.
 */
class GatewayTokenBudgetLiveAccountingTest {

    private static final String ACTOR = "actor-1";

    private static final String CLEAN = "Summarize quarterly revenue trends.";

    private static final String EMAIL = "alice@example.com";

    /** A provider total deliberately different from the reserved amount. */
    private static final long REPORTED_TOTAL = 700L;

    private static final long RESERVATION = 400L;

    private static final long DAILY_LIMIT = 1_000L;

    private GatewayTokenBudget budget;

    private GatewayUsagePolicyResolver policyResolver;

    private LlmProviderSelector selector;

    private LlmProvider selected;

    private GatewayAuditService audit;

    private GatewayUsageRecorder usage;

    private AlwaysAdmitsPolicyCounter counter;

    private GatewayCompletionService service;

    @BeforeEach
    void setUp() {
        budget = new InMemoryGatewayTokenBudget();
        policyResolver = mock(GatewayUsagePolicyResolver.class);
        selector = mock(LlmProviderSelector.class);
        selected = mock(LlmProvider.class);
        audit = mock(GatewayAuditService.class);
        usage = mock(GatewayUsageRecorder.class);
        counter = new AlwaysAdmitsPolicyCounter();

        when(selector.select(any())).thenReturn(selected);
        when(policyResolver.resolve(any())).thenReturn(GatewayUsagePolicyResolution.none());
        service = serviceWith(counter);
    }

    private static PiiDetectorRegistry detectors() {
        return new PiiDetectorRegistry(List.of(new EmailDetector()));
    }

    private static DefaultSecretDetector secrets() {
        return new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector());
    }

    private GatewayCompletionService serviceWith(GatewayUsagePolicyCounter requestCounter) {
        return new GatewayCompletionService(
                actor -> true,
                new GatewayUsagePolicyEnforcementService(policyResolver, requestCounter),
                new GatewayTokenBudgetEnforcementService(policyResolver, budget),
                new GatewayTokenBudgetSettlementService(budget),
                new SecurityInspectionService(detectors(), secrets()),
                new ProviderResponseInspectionService(detectors(), secrets()),
                selector,
                audit,
                usage);
    }

    private static GatewayInspectionRequest inspection() {
        return new GatewayInspectionRequest(UUID.randomUUID(), ACTOR, "test-model", CLEAN);
    }

    private void policy(Long requestsPerMinute, long dailyLimit, long reservation) {
        when(policyResolver.resolve(any())).thenReturn(GatewayUsagePolicyResolution.resolved(new GatewayUsagePolicy(
                ACTOR, "live", null, requestsPerMinute, null, dailyLimit, reservation, true)));
    }

    private void tokenOnlyPolicy(long dailyLimit, long reservation) {
        policy(null, dailyLimit, reservation);
    }

    /** Whether the real budget would admit this much capacity for the actor. */
    private boolean budgetAdmits(long requestedTokens) {
        Instant dayStart = GatewayTokenBudgetWindow.DAY.windowStart(Instant.now());
        GatewayTokenBudgetReservation attempt = budget.tryReserve(ACTOR, dayStart, DAILY_LIMIT, requestedTokens);
        if (attempt.isReserved()) {
            // Hand the probe's own capacity straight back, so asking changes
            // nothing about the day's accounting.
            budget.reconcile(ACTOR, dayStart, attempt.reservationId(), 0L);
        }
        return attempt.isReserved();
    }

    private void providerReports(Long totalTokens) {
        when(selected.complete(any())).thenReturn(new LlmResponse(
                "test-model",
                "Quarterly revenue grew.",
                totalTokens == null
                        ? LlmUsage.unknown()
                        : new LlmUsage(100L, totalTokens - 100L, totalTokens)));
    }

    @Test
    void aTokenOnlyPolicyReservesReconcilesAndLetsTheRequestSucceed() {
        // The end-to-end proof that tokensPerDay is live: a policy with no
        // request window at all, reserved against before the provider ran and
        // reconciled against the provider's own figure afterwards.
        tokenOnlyPolicy(DAILY_LIMIT, RESERVATION);
        providerReports(REPORTED_TOTAL);

        var response = service.complete(inspection());

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        verify(selected, times(1)).complete(any());
        // The request counter was never consulted: this policy declares no
        // request limit, so there is none to consume.
        assertThat(counter.requests()).isEmpty();
        // The hold was replaced by exactly the reported 700, not left at the
        // reserved 400: 300 of the day is genuinely back.
        assertThat(budgetAdmits(300L)).isTrue();
        assertThat(budgetAdmits(301L)).isFalse();
        verify(usage, times(1)).record(any(), any(), eq(GatewayUsageOutcome.DELIVERED));
    }

    @Test
    void aTokenOnlyPolicyIsRefusedOnceTheDaysTokensAreSpent() {
        // One reservation of 400 with a 1,000 day: the day holds two.
        tokenOnlyPolicy(DAILY_LIMIT, RESERVATION);
        providerReports(null);

        service.complete(inspection());
        service.complete(inspection());
        assertThatThrownBy(() -> service.complete(inspection()))
                .isInstanceOf(GatewayTokenBudgetLimitExceededException.class)
                .hasMessage("Gateway token budget exceeded.");

        // A refused reservation never reaches a provider and never records
        // usage, so exactly two provider calls and two usage rows exist.
        verify(selected, times(2)).complete(any());
        verify(usage, times(2)).record(any(), any(), any());
    }

    @Test
    void aFailedProviderCallGivesTheWholeHoldBack() {
        // Nothing was generated, so the actor's day must not be shrunk for a
        // call that never produced anything.
        tokenOnlyPolicy(DAILY_LIMIT, RESERVATION);
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));

        assertThatThrownBy(() -> service.complete(inspection()))
                .isInstanceOf(GatewayProviderException.class);

        // The day is exactly as it was before the failed attempt.
        assertThat(budgetAdmits(DAILY_LIMIT)).isTrue();
    }

    @Test
    void anUnknownProviderTotalLeavesTheHoldStandingUntilTheDayExpires() {
        // The conservative direction: the response may well have cost tokens, so
        // the hold is neither released nor replaced by a guess.
        tokenOnlyPolicy(DAILY_LIMIT, RESERVATION);
        providerReports(null);

        service.complete(inspection());

        assertThat(budgetAdmits(RESERVATION)).isTrue();
        assertThat(budgetAdmits(DAILY_LIMIT)).isFalse();
    }

    @Test
    void aMixedPolicyRefusesOnTheRequestLimitWithoutTouchingTheTokenBudget() {
        // Independent controls, request admission first: a request-policy
        // refusal happens before any token capacity is even considered, so the
        // token budget is never consulted and never spends.
        policy(1L, DAILY_LIMIT, RESERVATION);
        providerReports(REPORTED_TOTAL);
        GatewayCompletionService spent = serviceWith(
                new AlwaysAdmitsPolicyCounter.AlwaysRejectingPolicyCounter());

        assertThatThrownBy(() -> spent.complete(inspection()))
                .isInstanceOf(GatewayUsagePolicyLimitExceededException.class);

        // The day is completely untouched: not one token was reserved for a
        // request that the request control refused.
        assertThat(budgetAdmits(DAILY_LIMIT)).isTrue();
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void aMixedPolicyRefusesOnTheTokenBudgetWhileRequestsAreStillAdmitted() {
        // The mirror image: the request window is nowhere near spent, and the
        // token budget refuses on its own.
        AlwaysAdmitsPolicyCounter requestCounter = new AlwaysAdmitsPolicyCounter();
        policy(1_000L, DAILY_LIMIT, RESERVATION);
        providerReports(null);
        GatewayCompletionService generous = serviceWith(requestCounter);

        generous.complete(inspection());
        generous.complete(inspection());
        assertThatThrownBy(() -> generous.complete(inspection()))
                .isInstanceOf(GatewayTokenBudgetLimitExceededException.class);

        // Three requests were admitted by the request control and the third was
        // still refused by the token one: the controls never compensate.
        assertThat(requestCounter.requests()).hasSize(3);
        verify(selected, times(2)).complete(any());
    }

    @Test
    void aSecurityBlockReservesNothingSoTheActorIsNeverChargedForIt() {
        // Reservation happens after inspection precisely so that a blocked
        // request leaves the day untouched.
        tokenOnlyPolicy(DAILY_LIMIT, RESERVATION);

        var response = service.complete(new GatewayInspectionRequest(
                UUID.randomUUID(), ACTOR, "test-model", "contact " + EMAIL + " for access."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        verify(selected, never()).complete(any());
        verify(usage, never()).record(any(), any(), any());
        assertThat(budgetAdmits(DAILY_LIMIT)).isTrue();
    }
}
