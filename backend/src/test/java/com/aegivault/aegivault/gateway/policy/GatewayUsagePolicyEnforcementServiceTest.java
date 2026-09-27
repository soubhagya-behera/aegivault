package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyEnforcementService} (no Spring
 * context, no database, no Redis, no gateway wiring). They pin the
 * orchestration only: the service builds <em>one</em> atomic counter request
 * from the resolved policy's configured request limits, calls the counter
 * once, and maps the single result onto its own states. Counter internals —
 * atomicity, increment semantics, window arithmetic — are deliberately left
 * to the counter's own tests, and the counter is a mock throughout.
 */
class GatewayUsagePolicyEnforcementServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private GatewayUsagePolicyResolver resolver;

    private GatewayUsagePolicyCounter counter;

    private GatewayUsagePolicyEnforcementService service;

    /** The single request the service handed to the counter, if any. */
    private GatewayUsagePolicyCounterRequest lastRequest;

    @BeforeEach
    void setUp() {
        resolver = mock(GatewayUsagePolicyResolver.class);
        counter = mock(GatewayUsagePolicyCounter.class);
        service = new GatewayUsagePolicyEnforcementService(resolver, counter);
        lastRequest = null;
        // Default: every configured window admits.
        stubCounter(null);
    }

    /**
     * Stubs the one atomic consume call, capturing the request and rejecting
     * on {@code rejectedOn} when the service actually requested that window.
     */
    private void stubCounter(GatewayUsagePolicyCounterWindow rejectedOn) {
        when(counter.tryConsume(any(GatewayUsagePolicyCounterRequest.class)))
                .thenAnswer(invocation -> {
                    GatewayUsagePolicyCounterRequest request = invocation.getArgument(0);
                    lastRequest = request;
                    return rejectedOn == null || !request.limits().containsKey(rejectedOn)
                            ? GatewayUsagePolicyCounterResult.allowed()
                            : GatewayUsagePolicyCounterResult.rejected(rejectedOn);
                });
    }

    private void failCounter() {
        when(counter.tryConsume(any(GatewayUsagePolicyCounterRequest.class)))
                .thenThrow(new GatewayUsagePolicyCounterUnavailableException(
                        new RuntimeException("redis-connection-refused-9z")));
    }

    private void resolved(GatewayUsagePolicy policy) {
        when(resolver.resolve("actor-1")).thenReturn(GatewayUsagePolicyResolution.resolved(policy));
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
    void aTokenOnlyPolicyMakesNoCounterCall() {
        // tokensPerDay is not enforced, and a policy that declares nothing
        // else gives the service no request limit to consume.
        resolved(new GatewayUsagePolicy("actor-1", "tokens-only", null, null, null, 5_000L, true));

        var result = service.enforce("actor-1", NOW);

        // The vacuous truth "no configured request limit refused this request"
        // — the token limit was never checked and no capacity was spent.
        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        verifyNoInteractions(counter);
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
    void bothConfiguredLimitsAllowedYieldAllowWithExactlyOneCounterCall() {
        resolved(policy(10L, 100L, true));

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.ALLOW);
        // One interaction, not one per window: the whole point of the atomic
        // multi-window operation.
        verify(counter, times(1)).tryConsume(any(GatewayUsagePolicyCounterRequest.class));
        verifyNoMoreInteractions(counter);
    }

    @Test
    void oneAtomicRequestCarriesBothLimitsTheActorAndTheInstant() {
        resolved(policy(10L, 100L, true));

        service.enforce("actor-1", NOW);

        assertThat(lastRequest).isNotNull();
        assertThat(lastRequest.actorSubject()).isEqualTo("actor-1");
        assertThat(lastRequest.now()).isEqualTo(NOW);
        assertThat(lastRequest.limits())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        GatewayUsagePolicyCounterWindow.DAY, 100L,
                        GatewayUsagePolicyCounterWindow.MINUTE, 10L));
    }

    @Test
    void aMinuteRejectionIsMappedFromTheSingleCounterResult() {
        resolved(policy(10L, 100L, true));
        stubCounter(GatewayUsagePolicyCounterWindow.MINUTE);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
        assertThat(result.isAdmitted()).isFalse();
        // Exactly one atomic attempt: there is no second window call to make.
        verify(counter, times(1)).tryConsume(any(GatewayUsagePolicyCounterRequest.class));
        verifyNoMoreInteractions(counter);
    }

    @Test
    void aDayRejectionIsMappedFromTheSingleCounterResult() {
        resolved(policy(10L, 100L, true));
        stubCounter(GatewayUsagePolicyCounterWindow.DAY);

        var result = service.enforce("actor-1", NOW);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyEnforcementResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
        verify(counter, times(1)).tryConsume(any(GatewayUsagePolicyCounterRequest.class));
        verifyNoMoreInteractions(counter);
    }

    @Test
    void aMinuteOnlyPolicyRequestsOnlyTheMinuteWindow() {
        resolved(policy(10L, null, true));

        assertThat(service.enforce("actor-1", NOW).isAdmitted()).isTrue();

        assertThat(lastRequest.limits())
                .containsExactly(entry(GatewayUsagePolicyCounterWindow.MINUTE, 10L));
    }

    @Test
    void aDayOnlyPolicyRequestsOnlyTheDayWindow() {
        resolved(policy(null, 100L, true));

        assertThat(service.enforce("actor-1", NOW).isAdmitted()).isTrue();

        assertThat(lastRequest.limits())
                .containsExactly(entry(GatewayUsagePolicyCounterWindow.DAY, 100L));
    }

    @Test
    void aCounterFailurePropagatesAsASafeEnforcementFailureNotARejection() {
        resolved(policy(10L, 100L, true));
        failCounter();

        // "Could not check" stays distinct from "limit exceeded": no result
        // object is produced at all, so it cannot be mistaken for REJECTED.
        assertThatThrownBy(() -> service.enforce("actor-1", NOW))
                .isInstanceOf(GatewayUsagePolicyEnforcementException.class)
                .hasMessage(GatewayUsagePolicyEnforcementException.MESSAGE)
                .hasMessage("Unable to enforce gateway usage policy.")
                .hasMessageNotContaining("redis-connection-refused-9z");
        // The failed attempt stops immediately: no retry against the counter.
        verify(counter, times(1)).tryConsume(any(GatewayUsagePolicyCounterRequest.class));
        verifyNoMoreInteractions(counter);
    }

    @Test
    void theEnforcementFailureMessageLeaksNoRedisOrPolicyDetail() {
        assertThat(GatewayUsagePolicyEnforcementException.MESSAGE)
                .doesNotContain("localhost", "6379", "actor-1", "aegivault", "INCR");
    }

    @Test
    void theSameTrimmedActorReachesBothResolverAndCounter() {
        resolved(policy(10L, 100L, true));

        service.enforce("  actor-1  ", NOW);

        // One actor identity for both lookups: a policy can never be enforced
        // against a different subject's capacity.
        verify(resolver).resolve("actor-1");
        assertThat(lastRequest.actorSubject()).isEqualTo("actor-1");
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
        // pass. Admission must use the atomic counter instead.
        var fieldTypes = java.util.Arrays.stream(
                        GatewayUsagePolicyEnforcementService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();
        assertThat(fieldTypes).doesNotContain(GatewayUsagePolicyEvaluator.class.getName());
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

    @Test
    void theEnforcementResultCarriesNoActorRedisOrLimitDetail() {
        // It now also carries the policy id and the enforced window names for
        // the audit ledger, so this pins the whole shape: still no actor
        // subject, no counter value, no key, no configured limit.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEnforcementResult.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("state", "rejectedWindow", "policyId", "enforcedWindows");
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEnforcementResult.State.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("NO_POLICY", "ALLOW", "REJECTED", "INACTIVE");
        assertThat(GatewayUsagePolicyEnforcementResult.noPolicy().toString())
                .doesNotContain("redis", "localhost", "actor");
    }
}
