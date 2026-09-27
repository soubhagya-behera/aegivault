package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Pure unit tests for {@link GatewayUsagePolicyEnforcementService} (no Spring
 * context, no database, no Redis, no gateway wiring). They pin that capacity
 * is consumed only when a limit actually applies, that both configured
 * request limits must admit, that a rejection short-circuits the remaining
 * limit, that ambiguity and unavailability stay distinct from a rejection,
 * and that the service cannot have grown a dependency on the gateway traffic
 * flow.
 */
class GatewayUsagePolicyEnforcementServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private GatewayUsagePolicyResolver resolver;

    private GatewayUsagePolicyCounter counter;

    private GatewayUsagePolicyEnforcementService service;

    @BeforeEach
    void setUp() {
        resolver = mock(GatewayUsagePolicyResolver.class);
        counter = mock(GatewayUsagePolicyCounter.class);
        service = new GatewayUsagePolicyEnforcementService(resolver, counter);
    }

    private void resolved(GatewayUsagePolicy policy) {
        when(resolver.resolve("actor-1")).thenReturn(GatewayUsagePolicyResolution.resolved(policy));
    }

    private void admit(GatewayUsagePolicyCounterWindow window, long limit) {
        when(counter.tryConsume("actor-1", window, limit)).thenReturn(true);
    }

    private void reject(GatewayUsagePolicyCounterWindow window, long limit) {
        when(counter.tryConsume("actor-1", window, limit)).thenReturn(false);
    }

    private static GatewayUsagePolicy policy(Long perMinute, Long perDay, boolean enabled) {
        return new GatewayUsagePolicy("actor-1", "policy", null, perMinute, perDay, null, enabled);
    }

    @Test
    void noPolicyConsumesNothingAndIsNotReportedAsAllow() {
        when(resolver.resolve("actor-1")).thenReturn(GatewayUsagePolicyResolution.none());

        var result = service.enforce("actor-1", NOW);

        // No limit governs this request, so no capacity is spent and the
        // caller can still tell "no policy" from "limits satisfied".
        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.NO_POLICY);
        assertThat(result.state()).isNotEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        assertThat(result.isAdmitted()).isFalse();
        assertThat(result.rejectedWindow()).isNull();
        verifyNoInteractions(counter);
    }

    @Test
    void aDisabledPolicyIsInactiveAndConsumesNothing() {
        // The resolver only returns enabled policies, so this exercises the
        // guard rather than a live path — but the invariant matters: applying
        // nothing must never cost capacity.
        resolved(policy(10L, 100L, false));

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.INACTIVE);
        assertThat(result.rejectedWindow()).isNull();
        verifyNoInteractions(counter);
    }

    @Test
    void aMinuteOnlyPolicyAllowedByTheMinuteCounterIsAllowed() {
        resolved(policy(10L, null, true));
        admit(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        assertThat(result.isAdmitted()).isTrue();
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L);
    }

    @Test
    void aMinuteOnlyPolicyRejectedByTheMinuteCounterIsRejectedOnMinute() {
        resolved(policy(10L, null, true));
        reject(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
        assertThat(result.isAdmitted()).isFalse();
    }

    @Test
    void aDayOnlyPolicyAllowedByTheDayCounterIsAllowed() {
        resolved(policy(null, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.DAY, 100L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
    }

    @Test
    void aDayOnlyPolicyRejectedByTheDayCounterIsRejectedOnDay() {
        resolved(policy(null, 100L, true));
        reject(GatewayUsagePolicyCounterWindow.DAY, 100L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
    }

    @Test
    void aDayOnlyPolicyNeverTouchesTheMinuteCounter() {
        resolved(policy(null, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.DAY, 100L);

        service.enforce("actor-1", NOW);

        // An unset limit is unconstrained and is skipped, not checked with a
        // made-up number.
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        verifyNoMoreInteractions(counter);
    }

    @Test
    void aMinuteOnlyPolicyNeverTouchesTheDayCounter() {
        resolved(policy(10L, null, true));
        admit(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        service.enforce("actor-1", NOW);

        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L);
        verifyNoMoreInteractions(counter);
    }

    @Test
    void bothLimitsAllowedYieldsAllowAndConsumesBoth() {
        resolved(policy(10L, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.DAY, 100L);
        admit(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L);
    }

    @Test
    void minuteAcceptedButDayRejectedIsRejectedOnDay() {
        resolved(policy(10L, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.DAY, 100L);
        reject(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        var result = service.enforce("actor-1", NOW);

        // Both limits must admit; one rejection decides the outcome. This is
        // also the documented non-transactional case: the day's unit was
        // already consumed and is not returned.
        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
        assertThat(result.isAdmitted()).isFalse();
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L);
    }

    @Test
    void dayAcceptedButMinuteRejectedIsRejectedOnMinute() {
        resolved(policy(10L, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.MINUTE, 10L);
        reject(GatewayUsagePolicyCounterWindow.DAY, 100L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
    }

    @Test
    void aDayRejectionShortCircuitsBeforeTheMinuteCounter() {
        // Day is evaluated first, so a day rejection must not spend minute
        // capacity on a request that is already refused.
        resolved(policy(10L, 100L, true));
        reject(GatewayUsagePolicyCounterWindow.DAY, 100L);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        verifyNoMoreInteractions(counter);
    }

    @Test
    void bothLimitsAreEvaluatedInAFixedDayThenMinuteOrder() {
        resolved(policy(10L, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.DAY, 100L);
        admit(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        service.enforce("actor-1", NOW);

        // The order is deterministic so behavior does not depend on hashing
        // or reflection order.
        InOrder order = inOrder(counter);
        order.verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        order.verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void ambiguityPropagatesUnchangedAndConsumesNothing() {
        when(resolver.resolve("actor-1")).thenThrow(new GatewayUsagePolicyAmbiguousException());

        assertThatThrownBy(() -> service.enforce("actor-1", NOW))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class)
                .hasMessage("Multiple enabled gateway usage policies are configured.");

        // An undecidable configuration must not spend capacity, and must not
        // be converted into a rejection.
        verifyNoInteractions(counter);
    }

    @Test
    void aCounterFailurePropagatesAsASafeEnforcementFailureNotARejection() {
        resolved(policy(10L, null, true));
        when(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L))
                .thenThrow(new GatewayUsagePolicyCounterUnavailableException(
                        new RuntimeException("redis-connection-refused-9z")));

        // "Could not check" stays distinct from "limit exceeded": no result
        // object is produced at all, so it cannot be mistaken for REJECTED.
        assertThatThrownBy(() -> service.enforce("actor-1", NOW))
                .isInstanceOf(GatewayUsagePolicyEnforcementException.class)
                .hasMessage(GatewayUsagePolicyEnforcementException.MESSAGE)
                .hasMessage("Unable to enforce gateway usage policy.")
                .hasMessageNotContaining("redis-connection-refused-9z");
    }

    @Test
    void theEnforcementFailureMessageLeaksNoRedisOrPolicyDetail() {
        assertThat(GatewayUsagePolicyEnforcementException.MESSAGE)
                .doesNotContain("localhost", "6379", "actor-1", "aegivault", "INCR");
    }

    @Test
    void aCounterFailureStopsTheCallImmediately() {
        // No second counter is touched after a failure: continuing would
        // consume more capacity for a request that cannot be admitted.
        resolved(policy(10L, 100L, true));
        when(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L))
                .thenThrow(new GatewayUsagePolicyCounterUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(() -> service.enforce("actor-1", NOW))
                .isInstanceOf(GatewayUsagePolicyEnforcementException.class);
        // Exactly one attempt: the minute counter is never reached.
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        verifyNoMoreInteractions(counter);
    }

    @Test
    void theSameTrimmedActorReachesBothResolverAndCounter() {
        resolved(policy(10L, 100L, true));
        admit(GatewayUsagePolicyCounterWindow.DAY, 100L);
        admit(GatewayUsagePolicyCounterWindow.MINUTE, 10L);

        service.enforce("  actor-1  ", NOW);

        // One actor identity for both lookups: a policy can never be enforced
        // against a different subject's capacity.
        verify(resolver).resolve("actor-1");
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L);
        verify(counter).tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 10L);
    }

    @Test
    void aBlankActorIsRejectedBeforeAnyCounterIsTouched() {
        assertThatThrownBy(() -> service.enforce(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> service.enforce("   ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoInteractions(resolver, counter);
    }

    @Test
    void aNullNowIsRejectedBeforeAnyCounterIsTouched() {
        assertThatThrownBy(() -> service.enforce("actor-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("now must not be null");
        verifyNoInteractions(resolver, counter);
    }

    @Test
    void aNullResolutionIsRejectedRatherThanTreatedAsNoPolicy() {
        // A broken contract must not silently mean "no policy" and let the
        // request through uncharged.
        when(resolver.resolve("actor-1")).thenReturn(null);

        assertThatNullPointerException()
                .isThrownBy(() -> service.enforce("actor-1", NOW))
                .withMessage("resolver must not return a null resolution");
        verifyNoInteractions(counter);
    }

    @Test
    void aNullCollaboratorIsRejectedAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GatewayUsagePolicyEnforcementService(null, counter))
                .withMessage("resolver must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new GatewayUsagePolicyEnforcementService(resolver, null))
                .withMessage("counter must not be null");
    }

    @Test
    void theServiceDependsOnlyOnTheResolverAndTheCounter() {
        // Guards the dependency direction: no completion service, controller,
        // repository, Redis, provider, PII detector, or audit ledger may be
        // reachable from here, or enforcement would grow a path into the
        // gateway traffic flow.
        var dependencies = java.util.Arrays.stream(
                        GatewayUsagePolicyEnforcementService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies).containsExactlyInAnyOrder(
                GatewayUsagePolicyResolver.class.getName(),
                GatewayUsagePolicyCounter.class.getName());
    }

    @Test
    void admissionNeverGoesThroughTheObservationalEvaluator() {
        // The evaluator reads a persisted snapshot, which is after the fact;
        // two simultaneous requests would both see the same count and both
        // pass. Admission must use the atomic counter instead, so the
        // evaluator is neither a field nor a called collaborator here.
        var dependencies = java.util.Arrays.stream(
                        GatewayUsagePolicyEnforcementService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();
        assertThat(dependencies).doesNotContain(GatewayUsagePolicyEvaluator.class.getName());
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEnforcementService.class.getDeclaredMethods())
                        .filter(method -> !java.lang.reflect.Modifier.isPrivate(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName)
                        .toList())
                .containsExactly("enforce");
    }

    @Test
    void aTokenOnlyPolicyConsumesNoRequestCapacity() {
        // tokensPerDay alone declares no request limit, so there is nothing to
        // consume at admission time. ALLOW here is the vacuous truth "no
        // configured request limit refused this request" — it does NOT mean the
        // token limit was checked, and no capacity is spent.
        GatewayUsagePolicy tokenOnly =
                new GatewayUsagePolicy("actor-1", "tokens-only", null, null, null, 5_000L, true);
        resolved(tokenOnly);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        assertThat(result.rejectedWindow()).isNull();
        // The token limit is never turned into a counter call or an estimate.
        verifyNoInteractions(counter);
    }

    @Test
    void theResultCarriesNoActorRedisOrLimitDetail() {
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEnforcementResult.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("state", "rejectedWindow");
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEnforcementResult.State.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("NO_POLICY", "ALLOW", "REJECTED", "INACTIVE");
    }

    @Test
    void aRejectionAlwaysNamesTheWindowAndOtherStatesNeverDo() {
        assertThat(GatewayUsagePolicyEnforcementResult
                        .rejected(GatewayUsagePolicyCounterWindow.MINUTE)
                        .rejectedWindow())
                .isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
        // A REJECTED without a window could not be acted on, and a window on a
        // non-rejection would imply a limit was consulted when none was.
        assertThatThrownBy(() -> new GatewayUsagePolicyEnforcementResult(
                        GatewayUsagePolicyEnforcementResult.State.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsagePolicyEnforcementResult(
                        GatewayUsagePolicyEnforcementResult.State.ALLOW, GatewayUsagePolicyCounterWindow.DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theServiceIsNotASpringBeanAndIsNotWiredIntoTheGateway() {
        // No @Service/@Component, so the application context cannot pick it
        // up: nothing in the completion or rate-limit path can reach it, and
        // no live request is affected.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEnforcementService.class.getAnnotations())
                        .map(annotation -> annotation.annotationType().getName())
                        .toList())
                .isEmpty();
    }
}
