package com.aegivault.aegivault.gateway.policy.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayTokenBudgetSettlementService} — no Spring
 * context, no database, no Redis, no network, no provider.
 *
 * <p>Each test states the accounting property that makes settlement honest
 * (real usage is recorded exactly, an absent response gives capacity back, and
 * unestablishable usage is never invented) and then asserts the consequence, so
 * the internals can move without rewriting the expectations.
 */
class GatewayTokenBudgetSettlementServiceTest {

    /** 2026-03-15T12:30:45.123Z — the instant the reservation was taken at. */
    private static final Instant RESERVED_AT = Instant.parse("2026-03-15T12:30:45.123Z");

    /** The UTC day containing {@link #RESERVED_AT}. */
    private static final Instant DAY_START = Instant.parse("2026-03-15T00:00:00Z");

    private static final String ACTOR = "actor-1";

    private static final String ID = "res-1";

    private GatewayTokenBudget budget;

    private GatewayTokenBudgetSettlementService service;

    @BeforeEach
    void setUp() {
        budget = mock(GatewayTokenBudget.class);
        service = new GatewayTokenBudgetSettlementService(budget);
    }

    @Test
    void knownUsageBelowTheReservationIsReconciledExactly() {
        GatewayTokenBudgetSettlementResult result =
                service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(80L));

        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetSettlementResult.State.RECONCILED);
        assertThat(result.reservationId()).isEqualTo(ID);
        // The provider's own figure, so the 20 unused tokens go back.
        verify(budget).reconcile(ACTOR, DAY_START, ID, 80L);
    }

    @Test
    void knownUsageEqualToTheReservationIsReconciledExactly() {
        service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(100L));

        verify(budget).reconcile(ACTOR, DAY_START, ID, 100L);
    }

    @Test
    void knownUsageAboveTheReservationIsReconciledWithoutClamping() {
        GatewayTokenBudgetSettlementResult result =
                service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(140L));

        // The provider really spent 140, so 140 is what is recorded — capping
        // it back to the 100 reserved would understate the cost.
        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetSettlementResult.State.RECONCILED);
        verify(budget).reconcile(ACTOR, DAY_START, ID, 140L);
    }

    @Test
    void aReportedZeroIsSettledNotTreatedAsUnknown() {
        service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(0L));

        // A provider can legitimately report no tokens, which is a measurement,
        // not a missing one.
        verify(budget).reconcile(ACTOR, DAY_START, ID, 0L);
    }

    @Test
    void theActorWindowAndReservationArePassedThroughUnchanged() {
        service.settle("  " + ACTOR + "  ", RESERVED_AT, "  " + ID + "  ",
                GatewayTokenBudgetSettlement.withUsage(42L));

        // Trimmed exactly like the rest of the policy package, so a settlement
        // cannot address a differently-spelled actor or reservation.
        verify(budget).reconcile(ACTOR, DAY_START, ID, 42L);
    }

    @Test
    void theWindowIsDerivedFromTheSuppliedInstantNotAnInternalClock() {
        // Two reservations taken on different UTC days are each settled against
        // their own day, so the window follows the caller's instant rather than
        // whatever "now" happens to be when the provider returns.
        service.settle(ACTOR, Instant.parse("2026-03-15T12:00:00Z"), ID,
                GatewayTokenBudgetSettlement.withUsage(10L));
        service.settle(ACTOR, Instant.parse("2026-03-16T00:05:00Z"), "res-2",
                GatewayTokenBudgetSettlement.withUsage(20L));

        verify(budget).reconcile(
                ACTOR, Instant.parse("2026-03-15T00:00:00Z"), ID, 10L);
        verify(budget).reconcile(
                ACTOR, Instant.parse("2026-03-16T00:00:00Z"), "res-2", 20L);
    }

    @Test
    void unknownUsageLeavesTheReservationHeldAndNeverReconciles() {
        GatewayTokenBudgetSettlementResult result =
                service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.unknownUsage());

        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetSettlementResult.State.UNKNOWN_USAGE);
        assertThat(result.reservationId()).isEqualTo(ID);
        // The hold is neither settled at a guess nor at zero: the budget is not
        // touched at all, so the reservation simply rides out its day TTL.
        verifyNoInteractions(budget);
    }

    @Test
    void aProviderFailureReleasesTheReservationAtZero() {
        GatewayTokenBudgetSettlementResult result =
                service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.noResponse());

        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetSettlementResult.State.RELEASED);
        // Nothing was generated, so nothing was consumed and the capacity goes
        // back rather than being lost for the rest of the day.
        verify(budget).reconcile(ACTOR, DAY_START, ID, 0L);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void aSecurityBlockedResponseWithKnownTokensIsSettledLikeAnyOther() {
        // The provider produced a response, so it may have spent tokens; the
        // gateway's verdict on that response is irrelevant to the accounting,
        // which is why a block is not a distinct outcome here.
        GatewayTokenBudgetSettlementResult result =
                service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(64L));

        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetSettlementResult.State.RECONCILED);
        verify(budget).reconcile(ACTOR, DAY_START, ID, 64L);
    }

    @Test
    void aSecurityBlockedResponseWithUnknownTokensKeepsTheReservation() {
        GatewayTokenBudgetSettlementResult result =
                service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.unknownUsage());

        // Same as any other response with no reported count: held, not released,
        // because releasing would forgive spend that may genuinely have happened.
        assertThat(result.state())
                .isEqualTo(GatewayTokenBudgetSettlementResult.State.UNKNOWN_USAGE);
        verifyNoInteractions(budget);
    }

    @Test
    void aNegativeReportedTotalIsRejectedAndNothingIsSettled() {
        assertThatThrownBy(() -> GatewayTokenBudgetSettlement.withUsage(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalTokens must not be negative");
        verifyNoInteractions(budget);
    }

    @Test
    void aTokenCountWithoutAResponseIsRejectedAsContradictory() {
        // No response means nothing was generated, so a reported count alongside
        // one is incoherent; guessing which the caller meant would be worse.
        assertThatThrownBy(() -> new GatewayTokenBudgetSettlement(
                        GatewayProviderPhaseOutcome.NO_RESPONSE, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalTokens requires a produced provider response");
    }

    @Test
    void anUnavailableBudgetFailsRatherThanClaimingASettlement() {
        when(budget.reconcile(anyString(), any(), anyString(), anyLong()))
                .thenThrow(new GatewayTokenBudgetUnavailableException(
                        new RuntimeException("redis-connection-refused-9z")));

        assertThatThrownBy(() -> service.settle(
                        ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(80L)))
                .isInstanceOf(GatewayTokenBudgetSettlementException.class)
                .hasMessage(GatewayTokenBudgetSettlementException.MESSAGE)
                .hasMessageNotContaining("redis-connection-refused-9z");
        // Exactly one attempt: a failed reconciliation leaves server state
        // unknown, so retrying could settle the same tokens twice.
        verify(budget).reconcile(ACTOR, DAY_START, ID, 80L);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void anUnknownOrAlreadySettledReservationPropagatesUnchanged() {
        when(budget.reconcile(anyString(), any(), anyString(), anyLong()))
                .thenThrow(new GatewayTokenBudgetReservationStateException());

        // Not swallowed as an unknown-usage outcome: that would disguise a
        // caller-state error as ordinary uncertainty.
        assertThatThrownBy(() -> service.settle(
                        ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(80L)))
                .isInstanceOf(GatewayTokenBudgetReservationStateException.class)
                .isNotInstanceOf(GatewayTokenBudgetSettlementException.class);
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnythingIsSettled() {
        GatewayTokenBudgetSettlement known = GatewayTokenBudgetSettlement.withUsage(10L);

        assertThatThrownBy(() -> service.settle(null, RESERVED_AT, ID, known))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> service.settle("   ", RESERVED_AT, ID, known))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> service.settle(ACTOR, null, ID, known))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reservedAt must not be null");
        assertThatThrownBy(() -> service.settle(ACTOR, RESERVED_AT, null, known))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reservationId must not be null");
        assertThatThrownBy(() -> service.settle(ACTOR, RESERVED_AT, "   ", known))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationId must not be blank");
        assertThatThrownBy(() -> service.settle(ACTOR, RESERVED_AT, ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("settlement must not be null");

        verifyNoInteractions(budget);
    }

    @Test
    void aNullBudgetIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new GatewayTokenBudgetSettlementService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("budget must not be null");
    }

    @Test
    void theServiceDependsOnlyOnTheTokenBudget() {
        // Settlement reads no policy and reserves nothing, so its only
        // collaborator is the budget primitive itself.
        assertThat(Arrays.stream(GatewayTokenBudgetSettlementService.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(field -> field.getType().getName()))
                .containsExactly(GatewayTokenBudget.class.getName());
    }

    @Test
    void theServiceIsNotASpringBeanAndNeverReserves() {
        // No @Service/@Component: nothing wires it up yet, so it has no runtime
        // effect. And it only ever reconciles an existing hold — it must never
        // create one.
        assertThat(GatewayTokenBudgetSettlementService.class.getAnnotations()).isEmpty();

        service.settle(ACTOR, RESERVED_AT, ID, GatewayTokenBudgetSettlement.withUsage(80L));
        verify(budget, never()).tryReserve(anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void theResultExposesNoActorPolicyOrBudgetDetail() {
        assertThat(Arrays.stream(GatewayTokenBudgetSettlementResult.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyInAnyOrder("state", "reservationId");
    }
}