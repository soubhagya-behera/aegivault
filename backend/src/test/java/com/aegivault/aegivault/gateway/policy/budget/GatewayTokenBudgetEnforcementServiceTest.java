package com.aegivault.aegivault.gateway.policy.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.gateway.GatewayCompletionService;
import com.aegivault.aegivault.gateway.GatewayRateLimiter;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicy;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyAmbiguousException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyRepository;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolution;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for {@link GatewayTokenBudgetEnforcementService} — no Spring
 * context, no database, no Redis, no network, no provider.
 *
 * <p>Each test states the property that makes the coordinator safe (only a
 * real token limit reserves, the amount is the policy's own, the day is the
 * fixed UTC one, and a failure is never a rejection) and then asserts the
 * consequence, so a later wiring change can move the internals without
 * rewriting them. The last group asserts the service's dependency direction
 * by reflection, mirroring how the other policy services are pinned.
 */
class GatewayTokenBudgetEnforcementServiceTest {

    /** 2026-03-15T12:30:45.123Z — mid-day, so the day start is not the instant. */
    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    /** The UTC day containing {@link #NOW}. */
    private static final Instant DAY_START = Instant.parse("2026-03-15T00:00:00Z");

    private static final String ACTOR = "actor-1";

    private GatewayUsagePolicyResolver resolver;

    private GatewayTokenBudget budget;

    private GatewayTokenBudgetEnforcementService service;

    @BeforeEach
    void setUp() {
        resolver = mock(GatewayUsagePolicyResolver.class);
        budget = mock(GatewayTokenBudget.class);
        service = new GatewayTokenBudgetEnforcementService(resolver, budget);
    }

    /** Stubs the resolver with one policy and the budget with a successful hold. */
    private void tokenPolicySucceeds(long limit, long reservation) {
        resolves(policy(limit, reservation, true));
        when(budget.tryReserve(anyString(), any(), anyLong(), anyLong()))
                .thenReturn(GatewayTokenBudgetReservation.reserved("res-1", reservation));
    }

    private void resolves(GatewayUsagePolicy policy) {
        when(resolver.resolve(anyString()))
                .thenReturn(GatewayUsagePolicyResolution.resolved(policy));
    }

    private static GatewayUsagePolicy policy(Long tokensPerDay, Long reservation, boolean enabled) {
        return new GatewayUsagePolicy(ACTOR, "p", null, 60L, null, tokensPerDay, reservation, enabled);
    }

    @Test
    void aTokenPolicyReservesItsConfiguredAmount() {
        tokenPolicySucceeds(1_000_000L, 4_000L);

        GatewayTokenBudgetEnforcementResult result = service.reserve(ACTOR, NOW);

        assertThat(result.state()).isEqualTo(GatewayTokenBudgetEnforcementResult.State.RESERVED);
        assertThat(result.reservationId()).isEqualTo("res-1");
        assertThat(result.reservedTokens()).isEqualTo(4_000L);
        assertThat(result.isReserved()).isTrue();
        assertThat(result.admits()).isTrue();
    }

    @Test
    void theReservationUsesThePolicyAmountUnchanged() {
        tokenPolicySucceeds(1_000_000L, 4_000L);

        service.reserve(ACTOR, NOW);

        // The amount is exactly the policy's own value: no estimate, no
        // rounding, no scaling, and never the limit standing in for it.
        verify(budget).tryReserve(ACTOR, DAY_START, 1_000_000L, 4_000L);
    }

    @Test
    void theLimitComesStraightFromThePolicy() {
        tokenPolicySucceeds(500_000L, 250L);

        service.reserve(ACTOR, NOW);

        verify(budget).tryReserve(ACTOR, DAY_START, 500_000L, 250L);
    }

    @Test
    void theDayStartIsDerivedFromTheSuppliedInstantNotAClock() {
        // Two instants on either side of UTC midnight land in different days,
        // proving the window follows the caller's `now` exactly.
        tokenPolicySucceeds(1_000L, 100L);

        service.reserve(ACTOR, Instant.parse("2026-03-16T00:00:00Z"));
        service.reserve(ACTOR, Instant.parse("2026-03-15T23:59:59.999Z"));

        ArgumentCaptor<Instant> windows = ArgumentCaptor.forClass(Instant.class);
        verify(budget, times(2)).tryReserve(anyString(), windows.capture(), anyLong(), anyLong());
        assertThat(windows.getAllValues()).containsExactly(
                Instant.parse("2026-03-16T00:00:00Z"),
                Instant.parse("2026-03-15T00:00:00Z"));
    }

    @Test
    void anActorWithNoPolicyReservesNothing() {
        when(resolver.resolve(anyString())).thenReturn(GatewayUsagePolicyResolution.none());

        GatewayTokenBudgetEnforcementResult result = service.reserve(ACTOR, NOW);

        assertThat(result.state()).isEqualTo(GatewayTokenBudgetEnforcementResult.State.NO_POLICY);
        assertThat(result.reservationId()).isNull();
        assertThat(result.reservedTokens()).isZero();
        // The budget is never contacted: there is no allowance to spend.
        verifyNoInteractions(budget);
    }

    @Test
    void aDisabledPolicyReservesNothing() {
        resolves(policy(1_000L, 100L, false));

        GatewayTokenBudgetEnforcementResult result = service.reserve(ACTOR, NOW);

        assertThat(result.state()).isEqualTo(GatewayTokenBudgetEnforcementResult.State.INACTIVE);
        verifyNoInteractions(budget);
    }

    @Test
    void aPolicyWithoutADailyTokenLimitReservesNothing() {
        resolves(policy(null, null, true));

        GatewayTokenBudgetEnforcementResult result = service.reserve(ACTOR, NOW);

        // Distinct from REJECTED: this actor simply has no token rule, which is
        // not the same as having spent a budget.
        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetEnforcementResult.State.NO_TOKEN_POLICY);
        assertThat(result.admits()).isTrue();
        verifyNoInteractions(budget);
    }

    @Test
    void aBudgetRejectionIsReportedAsRejectedWithAClosedReason() {
        resolves(policy(1_000L, 100L, true));
        when(budget.tryReserve(anyString(), any(), anyLong(), anyLong()))
                .thenReturn(GatewayTokenBudgetReservation.rejected());

        GatewayTokenBudgetEnforcementResult result = service.reserve(ACTOR, NOW);

        assertThat(result.state()).isEqualTo(GatewayTokenBudgetEnforcementResult.State.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo(
                GatewayTokenBudgetEnforcementResult.RejectionReason.TOKEN_BUDGET_EXCEEDED);
        assertThat(result.admits()).isFalse();
        // A refusal holds nothing, so no identity is handed back.
        assertThat(result.reservationId()).isNull();
        assertThat(result.reservedTokens()).isZero();
    }

    @Test
    void anUnavailableBudgetFailsClosedRatherThanReportingARejection() {
        resolves(policy(1_000L, 100L, true));
        when(budget.tryReserve(anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new GatewayTokenBudgetUnavailableException(
                        new RuntimeException("redis-connection-refused-9z")));

        // "Could not check" must never read as "budget exceeded".
        assertThatThrownBy(() -> service.reserve(ACTOR, NOW))
                .isInstanceOf(GatewayTokenBudgetEnforcementException.class)
                .hasMessage(GatewayTokenBudgetEnforcementException.MESSAGE)
                .hasMessageNotContaining("redis-connection-refused-9z");
    }

    @Test
    void anAmbiguousPolicyPropagatesAndReservesNothing() {
        when(resolver.resolve(anyString())).thenThrow(new GatewayUsagePolicyAmbiguousException());

        assertThatThrownBy(() -> service.reserve(ACTOR, NOW))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class);
        // Picking arbitrarily between enabled policies could enforce the wrong
        // limit, so nothing is reserved at all.
        verifyNoInteractions(budget);
    }

    @Test
    void theActorIsTrimmedLikeTheRestOfThePolicyPackage() {
        resolves(policy(1_000L, 100L, true));
        when(budget.tryReserve(anyString(), any(), anyLong(), anyLong()))
                .thenReturn(GatewayTokenBudgetReservation.reserved("res-1", 100L));

        service.reserve("  " + ACTOR + "  ", NOW);

        // The trimmed subject is the one resolved and charged, so an untrimmed
        // spelling cannot spend another actor's budget.
        verify(resolver).resolve(ACTOR);
        verify(budget).tryReserve(ACTOR, DAY_START, 1_000L, 100L);
    }

    @Test
    void aBlankActorIsRejectedBeforeAnythingIsConsulted() {
        assertThatThrownBy(() -> service.reserve(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> service.reserve("   ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoInteractions(resolver, budget);
    }

    @Test
    void aNullInstantIsRejectedBeforeAnythingIsConsulted() {
        assertThatThrownBy(() -> service.reserve(ACTOR, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now must not be null");
        verifyNoInteractions(resolver, budget);
    }

    @Test
    void nullCollaboratorsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new GatewayTokenBudgetEnforcementService(null, budget))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resolver must not be null");
        assertThatThrownBy(() -> new GatewayTokenBudgetEnforcementService(resolver, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("budget must not be null");
    }

    @Test
    void noReconciliationIsPerformed() {
        tokenPolicySucceeds(1_000_000L, 4_000L);

        GatewayTokenBudgetEnforcementResult result = service.reserve(ACTOR, NOW);

        // Settling a hold against real usage belongs to the runtime step that
        // follows the provider call, so this coordinator must never call it.
        verify(budget, never()).reconcile(anyString(), any(), anyString(), anyLong());
        // The id is simply handed on for that later step.
        assertThat(result.reservationId()).isEqualTo("res-1");
    }

    @Test
    void nothingIsReservedAfterAFailure() {
        resolves(policy(1_000L, 100L, true));
        when(budget.tryReserve(anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new GatewayTokenBudgetUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(() -> service.reserve(ACTOR, NOW))
                .isInstanceOf(GatewayTokenBudgetEnforcementException.class);

        // The one attempt that failed is the only call: no retry, no second
        // probe, and nothing else touched afterwards.
        verify(budget).tryReserve(ACTOR, DAY_START, 1_000L, 100L);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void theServiceDependsOnlyOnTheResolverAndTheBudget() {
        Set<String> dependencies = Arrays.stream(
                        GatewayTokenBudgetEnforcementService.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(Collectors.toSet());

        // The whole point of the dependency boundary: no completion service, no
        // rate limiter, no request counter, no provider, no repository, no
        // audit ledger, and no Redis type is reachable from this coordinator.
        assertThat(dependencies).containsExactlyInAnyOrder(
                GatewayUsagePolicyResolver.class.getName(),
                GatewayTokenBudget.class.getName());
    }

    @Test
    void theCoordinatorSharesNoTypeWithAnyLiveGatewayComponent() {
        // Token enforcement is a separate control: it must not reuse the
        // request counter, the global rate limiter, or the usage recorder, or a
        // change to request admission could silently alter token accounting.
        assertThat(Arrays.stream(GatewayTokenBudgetEnforcementService.class.getDeclaredFields())
                        .map(field -> field.getType().getName()))
                .doesNotContain(
                        GatewayUsagePolicyCounter.class.getName(),
                        GatewayRateLimiter.class.getName(),
                        GatewayUsageRecorder.class.getName(),
                        GatewayCompletionService.class.getName(),
                        GatewayUsagePolicyRepository.class.getName());
    }

    @Test
    void theResultCarriesNoActorLimitOrRemainingBudget() {
        // A rejection must not tell the caller how much is left, so the result
        // type itself cannot: its surface is the state, the id, the amount this
        // one reservation holds, and a closed reason.
        assertThat(Arrays.stream(GatewayTokenBudgetEnforcementResult.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .containsExactlyInAnyOrder(
                        "state", "reservationId", "reservedTokens", "rejectionReason");
    }

    @Test
    void theResultRefusesToDescribeAnInconsistentOutcome() {
        // Only RESERVED may carry a hold, only REJECTED a reason: a state that
        // claimed capacity it never took, or a refusal that named a reason it
        // cannot have, is rejected at construction rather than stored.
        assertThatThrownBy(() -> new GatewayTokenBudgetEnforcementResult(
                        GatewayTokenBudgetEnforcementResult.State.RESERVED, null, 100L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayTokenBudgetEnforcementResult(
                        GatewayTokenBudgetEnforcementResult.State.REJECTED, "res-1", 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayTokenBudgetEnforcementResult(
                        GatewayTokenBudgetEnforcementResult.State.NO_POLICY, "res-1", 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayTokenBudgetEnforcementResult(
                        GatewayTokenBudgetEnforcementResult.State.RESERVED, "res-1", 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}