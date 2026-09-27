package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the atomic request/result model (no Spring context, no
 * storage, no gateway wiring). They pin the shape guarantees the multi-window
 * operation relies on: at most one limit per window, a fixed evaluation order
 * that never depends on map iteration, and the rule that a rejection always
 * names its window while other states never do.
 */
class GatewayUsagePolicyCounterRequestTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    @Test
    void bothWindowsAreEvaluatedInTheFixedDayThenMinuteOrder() {
        // Built from a map whose iteration order happens to be minute-first,
        // to prove the declared order is not simply the map's own order.
        EnumMap<GatewayUsagePolicyCounterWindow, Long> limits =
                new EnumMap<>(GatewayUsagePolicyCounterWindow.class);
        limits.put(GatewayUsagePolicyCounterWindow.MINUTE, 2L);
        limits.put(GatewayUsagePolicyCounterWindow.DAY, 100L);
        var request = new GatewayUsagePolicyCounterRequest("actor-1", NOW, new HashMap<>(limits));

        assertThat(request.windowsInEvaluationOrder())
                .containsExactly(GatewayUsagePolicyCounterWindow.DAY, GatewayUsagePolicyCounterWindow.MINUTE);
    }

    @Test
    void theEvaluationOrderIsAFixedPropertyOfTheWindowsNotOfTheLimits() {
        // Which window rejects first depends on runtime counts, so the order is
        // declared, never derived from configured limit values.
        var smallDay = GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, 1L, 999L);
        var largeDay = GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, 999_999L, 1L);

        assertThat(smallDay.windowsInEvaluationOrder()).isEqualTo(largeDay.windowsInEvaluationOrder());
        assertThat(GatewayUsagePolicyCounterWindow.DAY.evaluationRank())
                .isLessThan(GatewayUsagePolicyCounterWindow.MINUTE.evaluationRank());
    }

    @Test
    void duplicateWindowsAreNotExpressible() {
        // Limits live in an EnumMap keyed by window, so "consume two units
        // from the minute window in one request" cannot even be written.
        var request = GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, 10L, 20L);

        assertThat(request.limits()).hasSize(2);
        assertThat(request.limits())
                .containsEntry(GatewayUsagePolicyCounterWindow.DAY, 10L)
                .containsEntry(GatewayUsagePolicyCounterWindow.MINUTE, 20L);
    }

    @Test
    void theLimitsMapIsUnmodifiable() {
        var request = GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, 10L, 20L);

        assertThatThrownBy(() -> request.limits().put(GatewayUsagePolicyCounterWindow.DAY, 999L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theActorIsTrimmedAndABlankActorIsRejected() {
        var request = GatewayUsagePolicyCounterRequest.ofBoth("  actor-1  ", NOW, 10L, 20L);

        assertThat(request.actorSubject()).isEqualTo("actor-1");
        assertThatThrownBy(() -> GatewayUsagePolicyCounterRequest.ofBoth("   ", NOW, 10L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
    }

    @Test
    void aNullNowIsRejected() {
        assertThatThrownBy(() -> GatewayUsagePolicyCounterRequest.ofBoth("actor-1", null, 10L, 20L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now must not be null");
    }

    @Test
    void anEmptyOrNonPositiveLimitIsRejected() {
        // A request that asks to consume nothing would have nothing to decide,
        // and silently allowing it would report an admission that never
        // happened.
        assertThatThrownBy(() -> new GatewayUsagePolicyCounterRequest(
                        "actor-1", NOW, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limits must not be empty");
        assertThatThrownBy(() -> new GatewayUsagePolicyCounterRequest(
                        "actor-1", NOW, Map.of(GatewayUsagePolicyCounterWindow.DAY, 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> new GatewayUsagePolicyCounterRequest(
                        "actor-1", NOW, Map.of(GatewayUsagePolicyCounterWindow.DAY, -1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }

    @Test
    void aSingleWindowRequestNamesOnlyThatWindow() {
        var request = GatewayUsagePolicyCounterRequest.of(
                "actor-1", NOW, GatewayUsagePolicyCounterWindow.MINUTE, 5L);

        assertThat(request.windowsInEvaluationOrder())
                .containsExactly(GatewayUsagePolicyCounterWindow.MINUTE);
        assertThat(request.limits()).containsExactly(entry(GatewayUsagePolicyCounterWindow.MINUTE, 5L));
    }

    @Test
    void eachRequestedWindowResolvesToTheSameDocumentedKeyShape() {
        var request = GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, 100L, 2L);

        // Unchanged external key format:
        // aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>
        assertThat(request.keyFor(GatewayUsagePolicyCounterWindow.DAY))
                .isEqualTo("aegivault:gateway:policy-counter:day:1773532800:actor-1");
        assertThat(request.keyFor(GatewayUsagePolicyCounterWindow.MINUTE))
                .isEqualTo("aegivault:gateway:policy-counter:minute:1773577800:actor-1");
    }

    @Test
    void theRequestIsPolicyIndependent() {
        // It carries an actor, an instant, and per-window limits. It knows
        // nothing about policies, resolution, evaluation, or tokens.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyCounterRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("actorSubject", "now", "limits");
    }

    @Test
    void theResultHasExactlyTwoOutcomes() {
        assertThat(GatewayUsagePolicyCounterResult.allowed().isAllowed()).isTrue();
        assertThat(GatewayUsagePolicyCounterResult.allowed().rejectedWindow()).isNull();
        assertThat(GatewayUsagePolicyCounterResult.rejected(GatewayUsagePolicyCounterWindow.MINUTE).isAllowed())
                .isFalse();
        assertThat(GatewayUsagePolicyCounterResult.rejected(GatewayUsagePolicyCounterWindow.MINUTE).rejectedWindow())
                .isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyCounterResult.State.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("ALLOWED", "REJECTED");
    }

    @Test
    void aRejectionAlwaysNamesItsWindowAndAnAdmissionNeverDoes() {
        // A rejection without a window could not be acted on, and a window on
        // an admission would imply a limit was exhausted when none was.
        assertThatThrownBy(() -> new GatewayUsagePolicyCounterResult(
                        GatewayUsagePolicyCounterResult.State.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsagePolicyCounterResult(
                        GatewayUsagePolicyCounterResult.State.ALLOWED, GatewayUsagePolicyCounterWindow.DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
