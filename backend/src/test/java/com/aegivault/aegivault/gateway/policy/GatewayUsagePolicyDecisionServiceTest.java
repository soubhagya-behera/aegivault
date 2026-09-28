package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Pure unit tests for {@link GatewayUsagePolicyDecisionService} (no Spring
 * context, no database, no Redis, no gateway wiring, no HTTP). They pin the
 * composition itself: that the three existing pieces are ordered correctly,
 * that {@code NO_POLICY} stays distinct from {@code ALLOW}, that ambiguity
 * still fails rather than being decided, that the caller's exact instant
 * reaches the snapshot provider, and that the service cannot have grown a
 * dependency on the gateway traffic flow.
 */
class GatewayUsagePolicyDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private final GatewayUsagePolicyResolver resolver = mock(GatewayUsagePolicyResolver.class);

    private final GatewayUsagePolicyUsageSnapshotProvider snapshots =
            mock(GatewayUsagePolicyUsageSnapshotProvider.class);

    private final GatewayUsagePolicyDecisionService service =
            new GatewayUsagePolicyDecisionService(resolver, snapshots);

    private static GatewayUsagePolicy policy(String owner, Long perMinute, Long perDay, Long tokens, boolean enabled) {
        return new GatewayUsagePolicy(owner, "policy", null, perMinute, perDay, tokens, 1L, enabled);
    }

    private void resolved(GatewayUsagePolicy policy) {
        when(resolver.resolve("actor-1")).thenReturn(GatewayUsagePolicyResolution.resolved(policy));
    }

    private void usage(GatewayUsagePolicyUsageSnapshot snapshot) {
        when(snapshots.snapshotFor("actor-1", NOW)).thenReturn(snapshot);
    }

    @Test
    void noEnabledPolicyIsReportedAsNoPolicyAndNeverAsAllow() {
        when(resolver.resolve("actor-1")).thenReturn(GatewayUsagePolicyResolution.none());

        var outcome = service.decide("actor-1", NOW);

        // The distinction that matters: nothing was evaluated, so nothing was
        // satisfied. No usage is read, because the answer cannot depend on it.
        assertThat(outcome.state()).isEqualTo(GatewayUsagePolicyDecisionOutcome.State.NO_POLICY);
        assertThat(outcome.state()).isNotEqualTo(GatewayUsagePolicyDecisionOutcome.State.ALLOW);
        assertThat(outcome.isNoPolicy()).isTrue();
        assertThat(outcome.isEvaluated()).isFalse();
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.tokenUsageUnknown()).isFalse();
        verifyNoInteractions(snapshots);
    }

    @Test
    void oneEnabledPolicyAndCleanUsageYieldsAllow() {
        resolved(policy("actor-1", 10L, 100L, 1_000L, true));
        usage(GatewayUsagePolicyUsageSnapshot.withTokenUsage(3L, 20L, 400L));

        var outcome = service.decide("actor-1", NOW);

        assertThat(outcome.state()).isEqualTo(GatewayUsagePolicyDecisionOutcome.State.ALLOW);
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.tokenUsageUnknown()).isFalse();
    }

    @Test
    void oneEnabledPolicyAndExceededUsageYieldsLimitExceeded() {
        resolved(policy("actor-1", 10L, 100L, 1_000L, true));
        usage(GatewayUsagePolicyUsageSnapshot.withTokenUsage(11L, 200L, 1_001L));

        var outcome = service.decide("actor-1", NOW);

        assertThat(outcome.state()).isEqualTo(GatewayUsagePolicyDecisionOutcome.State.LIMIT_EXCEEDED);
        // Order comes from the evaluator, not from anything re-decided here.
        assertThat(outcome.violations()).containsExactly(
                GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE,
                GatewayUsagePolicyViolation.REQUESTS_PER_DAY,
                GatewayUsagePolicyViolation.TOKENS_PER_DAY);
    }

    @Test
    void unknownTokenUsageYieldsUsageUnknownNotLimitExceeded() {
        resolved(policy("actor-1", 10L, null, 1_000L, true));
        usage(GatewayUsagePolicyUsageSnapshot.withoutTokenUsage(3L, 20L));

        var outcome = service.decide("actor-1", NOW);

        assertThat(outcome.state()).isEqualTo(GatewayUsagePolicyDecisionOutcome.State.USAGE_UNKNOWN);
        assertThat(outcome.state()).isNotEqualTo(GatewayUsagePolicyDecisionOutcome.State.LIMIT_EXCEEDED);
        assertThat(outcome.state()).isNotEqualTo(GatewayUsagePolicyDecisionOutcome.State.ALLOW);
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.tokenUsageUnknown()).isTrue();
    }

    @Test
    void aDisabledPolicyYieldsInactiveEvenWhenUsageIsHuge() {
        // The resolver only ever hands back enabled policies, so a disabled
        // one can only reach the evaluator through a stub; the state itself is
        // still the evaluator's to decide, not this service's.
        resolved(policy("actor-1", 1L, 1L, 1L, false));
        usage(GatewayUsagePolicyUsageSnapshot.withTokenUsage(999L, 999L, 99_999L));

        var outcome = service.decide("actor-1", NOW);

        assertThat(outcome.state()).isEqualTo(GatewayUsagePolicyDecisionOutcome.State.INACTIVE);
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    void ambiguityPropagatesUnchangedAndIsNeverTurnedIntoADecision() {
        when(resolver.resolve("actor-1")).thenThrow(new GatewayUsagePolicyAmbiguousException());

        assertThatThrownBy(() -> service.decide("actor-1", NOW))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class)
                .hasMessage("Multiple enabled gateway usage policies are configured.");

        // Failing closed on an undecidable configuration means not guessing:
        // no usage read, so there is nothing to have picked a policy against.
        verifyNoInteractions(snapshots);
    }

    @Test
    void theSnapshotIsReadForTheSameActorThatThePolicyWasResolvedFor() {
        resolved(policy("actor-1", 10L, null, null, true));
        usage(GatewayUsagePolicyUsageSnapshot.withTokenUsage(1L, 1L, 1L));

        service.decide("actor-1", NOW);

        // Both collaborators see the identical actor string, so a policy can
        // never be evaluated against another actor's usage.
        verify(resolver).resolve("actor-1");
        verify(snapshots).snapshotFor("actor-1", NOW);
    }

    @Test
    void theCallersExactInstantReachesTheSnapshotProviderUnchanged() {
        resolved(policy("actor-1", 10L, null, null, true));
        usage(GatewayUsagePolicyUsageSnapshot.withTokenUsage(1L, 1L, 1L));

        // A deliberately odd instant: truncated, offset, and boundary-adjacent
        // instants would all look the same after any internal rounding, but
        // this one must arrive byte-for-byte identical.
        Instant exact = Instant.parse("2026-03-15T12:30:45.123456789Z");
        when(snapshots.snapshotFor("actor-1", exact))
                .thenReturn(GatewayUsagePolicyUsageSnapshot.withTokenUsage(1L, 1L, 1L));

        service.decide("actor-1", exact);

        verify(snapshots).snapshotFor("actor-1", Instant.parse("2026-03-15T12:30:45.123456789Z"));
    }

    @Test
    void theActorIsTrimmedOnceAndTheSameTrimmedValueReachesBothCollaborators() {
        resolved(policy("actor-1", 10L, null, null, true));
        usage(GatewayUsagePolicyUsageSnapshot.withTokenUsage(1L, 1L, 1L));

        service.decide("  actor-1\t ", NOW);

        // Same convention as the resolver and the provider, and one value for
        // both lookups: trimming twice or differently could split the actor.
        verify(resolver).resolve("actor-1");
        verify(snapshots).snapshotFor("actor-1", NOW);
    }

    @Test
    void aBlankActorIsRejectedBeforeAnyDependencyIsTouched() {
        assertThatThrownBy(() -> service.decide(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> service.decide("   ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoInteractions(resolver, snapshots);
    }

    @Test
    void aNullNowIsRejectedBeforeAnyDependencyIsTouched() {
        assertThatThrownBy(() -> service.decide("actor-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("now must not be null");
        verifyNoInteractions(resolver, snapshots);
    }

    @Test
    void aNullResolutionIsRejectedRatherThanTreatedAsNoPolicy() {
        // The resolver's own contract never returns null, but if a
        // collaborator ever broke it, "undecidable" must not silently become
        // "no policy" or, worse, "allowed".
        when(resolver.resolve("actor-1")).thenReturn(null);

        assertThatNullPointerException()
                .isThrownBy(() -> service.decide("actor-1", NOW))
                .withMessage("resolver must not return a null resolution");
        verifyNoInteractions(snapshots);
    }

    @Test
    void aNullSnapshotIsRejectedRatherThanTreatedAsNoUsage() {
        resolved(policy("actor-1", 10L, null, null, true));
        when(snapshots.snapshotFor("actor-1", NOW)).thenReturn(null);

        assertThatNullPointerException()
                .isThrownBy(() -> service.decide("actor-1", NOW))
                .withMessage("snapshots must not return a null snapshot");
    }

    @Test
    void aFailedResolutionStopsTheFlowBeforeAnyUsageIsRead() {
        // The earlier failure is not papered over by continuing to the next
        // step: nothing runs after it, so no half-decided state is observable.
        when(resolver.resolve("actor-1")).thenThrow(new GatewayUsagePolicyAmbiguousException());

        assertThatThrownBy(() -> service.decide("actor-1", NOW))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class);

        verify(snapshots, never()).snapshotFor(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aNullCollaboratorIsRejectedAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GatewayUsagePolicyDecisionService(null, snapshots))
                .withMessage("resolver must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new GatewayUsagePolicyDecisionService(resolver, null))
                .withMessage("snapshots must not be null");
    }

    @Test
    void theEvaluatorReceivesExactlyTheResolvedPolicyAndTheReturnedSnapshot() {
        // Only the exact pair the resolver and the provider actually returned
        // may reach the evaluator, so any substitution or re-derivation — a
        // freshly built policy, a re-read snapshot — is caught here.
        GatewayUsagePolicy resolvedPolicy = policy("actor-1", 10L, null, null, true);
        GatewayUsagePolicy otherPolicy = policy("actor-1", 999L, null, null, true);
        GatewayUsagePolicyUsageSnapshot snapshot = GatewayUsagePolicyUsageSnapshot.withTokenUsage(1L, 1L, 1L);
        GatewayUsagePolicyUsageSnapshot otherSnapshot =
                GatewayUsagePolicyUsageSnapshot.withTokenUsage(500L, 500L, 500L);
        resolved(resolvedPolicy);
        usage(snapshot);

        try (MockedStatic<GatewayUsagePolicyEvaluator> evaluator =
                mockStatic(GatewayUsagePolicyEvaluator.class)) {
            evaluator.when(() -> GatewayUsagePolicyEvaluator.evaluate(resolvedPolicy, snapshot))
                    .thenReturn(GatewayUsagePolicyDecision.allow());

            var outcome = service.decide("actor-1", NOW);

            assertThat(outcome.state()).isEqualTo(GatewayUsagePolicyDecisionOutcome.State.ALLOW);
            evaluator.verify(() -> GatewayUsagePolicyEvaluator.evaluate(resolvedPolicy, snapshot));
        }

        // The decoy pairs prove the verify above is meaningful: each of these
        // is a *different* call with different arguments, and swapping either
        // one in would not match. The service also compares no limits itself,
        // so the stubbed ALLOW is the only possible source of that answer.
        assertThat(GatewayUsagePolicyEvaluator.evaluate(resolvedPolicy, otherSnapshot).state())
                .isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(GatewayUsagePolicyEvaluator.evaluate(otherPolicy, otherSnapshot).state())
                .isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
    }

    @Test
    void theServiceDependsOnlyOnTheResolverAndTheSnapshotProvider() {
        // Guards the dependency direction: the only collaborators are the two
        // existing read-only policy pieces. There must be no path to the
        // completion service, the rate limiter, Redis, providers, PII
        // detectors, the audit ledger, repositories, or a controller.
        var dependencies = java.util.Arrays.stream(GatewayUsagePolicyDecisionService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies).containsExactlyInAnyOrder(
                GatewayUsagePolicyResolver.class.getName(),
                GatewayUsagePolicyUsageSnapshotProvider.class.getName());
    }

    @Test
    void theServiceIsNotASpringBeanAndIsNotWiredIntoTheGateway() {
        // No @Service/@Component/@Transactional, so the application context
        // cannot pick it up and no traffic path can reach it. The evaluator
        // stays a static utility it merely calls, never an injected bean.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyDecisionService.class.getAnnotations())
                        .map(annotation -> annotation.annotationType().getName())
                        .toList())
                .isEmpty();
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyDecisionService.class.getDeclaredMethods())
                        .filter(method -> !java.lang.reflect.Modifier.isPrivate(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName)
                        .toList())
                .containsExactly("decide");
    }

    @Test
    void theOutcomeCarriesNoActorOwnerPolicyOrContentFields() {
        // The result identifies a decision state and nothing else: no actor
        // subject, no owner, no policy id or label, no raw usage, no provider
        // content, and no monetary concepts.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyDecisionOutcome.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("state", "violations", "tokenUsageUnknown");
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyDecisionOutcome.State.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("NO_POLICY", "ALLOW", "LIMIT_EXCEEDED", "USAGE_UNKNOWN", "INACTIVE");
    }

    @Test
    void noPolicyCanNeverBeConstructedWithViolationsOrUnknownUsage() {
        // NO_POLICY means nothing was evaluated, so it cannot also claim a
        // violation or an unknown total; that contradiction is rejected.
        assertThatThrownBy(() -> new GatewayUsagePolicyDecisionOutcome(
                        GatewayUsagePolicyDecisionOutcome.State.NO_POLICY,
                        java.util.List.of(GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NO_POLICY must not report violations or unknown token usage");
        assertThatThrownBy(() -> new GatewayUsagePolicyDecisionOutcome(
                        GatewayUsagePolicyDecisionOutcome.State.NO_POLICY, java.util.List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NO_POLICY must not report violations or unknown token usage");
    }

    @Test
    void theOutcomeMirrorsTheEvaluatorRatherThanRedecidingIt() {
        // Every evaluator state maps 1:1 onto the outcome, and the outcome
        // never invents a state the evaluator itself cannot produce.
        var samples = java.util.List.of(
                GatewayUsagePolicyDecision.allow(),
                GatewayUsagePolicyDecision.limitExceeded(
                        java.util.List.of(GatewayUsagePolicyViolation.TOKENS_PER_DAY), true),
                GatewayUsagePolicyDecision.usageUnknown(),
                GatewayUsagePolicyDecision.inactive());

        for (GatewayUsagePolicyDecision decision : samples) {
            assertThat(GatewayUsagePolicyDecisionOutcome.of(decision).state().name())
                    .isEqualTo(decision.state().name());
            assertThat(GatewayUsagePolicyDecisionOutcome.of(decision).violations())
                    .isEqualTo(decision.violations());
            assertThat(GatewayUsagePolicyDecisionOutcome.of(decision).tokenUsageUnknown())
                    .isEqualTo(decision.tokenUsageUnknown());
        }
        // NO_POLICY exists only on the outcome, because it is a resolution
        // fact the evaluator never sees.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyDecision.State.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("ALLOW", "LIMIT_EXCEEDED", "USAGE_UNKNOWN", "INACTIVE");
    }
}
