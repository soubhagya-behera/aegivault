package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyEvaluator} (no Spring context,
 * no database, no HTTP, no Redis, no gateway wiring). They pin the decision
 * logic only: the boundary behavior of each limit, the explicit
 * unknown-token state, the disabled-policy state, and the deterministic
 * ordering when several limits trip at once.
 */
class GatewayUsagePolicyEvaluatorTest {

    private static GatewayUsagePolicy minuteOnly(Long limit) {
        return new GatewayUsagePolicy("actor-1", "minute", null, limit, null, null, true);
    }

    private static GatewayUsagePolicy dayOnly(Long limit) {
        return new GatewayUsagePolicy("actor-1", "day", null, null, limit, null, true);
    }

    private static GatewayUsagePolicy tokensOnly(Long limit) {
        return new GatewayUsagePolicy("actor-1", "tokens", null, null, null, limit, true);
    }

    private static GatewayUsagePolicyUsageSnapshot requests(long minute, long day) {
        return GatewayUsagePolicyUsageSnapshot.withoutTokenUsage(minute, day);
    }

    private static GatewayUsagePolicyUsageSnapshot withTokens(long minute, long day, long tokens) {
        return GatewayUsagePolicyUsageSnapshot.withTokenUsage(minute, day, tokens);
    }


    @Test
    void requestsPerMinuteBelowLimitAllows() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(minuteOnly(10L), requests(9L, 0L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
        assertThat(decision.violations()).isEmpty();
        assertThat(decision.isLimitExceeded()).isFalse();
    }

    @Test
    void requestsPerMinuteExactlyAtLimitAllows() {
        // A limit is the highest permitted value, so the boundary is allowed.
        var decision = GatewayUsagePolicyEvaluator.evaluate(minuteOnly(10L), requests(10L, 0L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    void requestsPerMinuteAboveLimitIsExceeded() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(minuteOnly(10L), requests(11L, 0L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.isLimitExceeded()).isTrue();
        assertThat(decision.violations()).containsExactly(GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE);
        assertThat(decision.violates(GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE)).isTrue();
    }

    @Test
    void requestsPerDayBelowLimitAllows() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(dayOnly(100L), requests(0L, 99L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
    }

    @Test
    void requestsPerDayExactlyAtLimitAllows() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(dayOnly(100L), requests(0L, 100L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
    }

    @Test
    void requestsPerDayAboveLimitIsExceeded() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(dayOnly(100L), requests(0L, 101L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.violations()).containsExactly(GatewayUsagePolicyViolation.REQUESTS_PER_DAY);
    }

    @Test
    void knownTokensBelowLimitAllows() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(tokensOnly(1000L), withTokens(0L, 0L, 999L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
        assertThat(decision.tokenUsageUnknown()).isFalse();
    }

    @Test
    void knownTokensExactlyAtLimitAllows() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(tokensOnly(1000L), withTokens(0L, 0L, 1000L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
    }

    @Test
    void knownTokensAboveLimitIsExceeded() {
        var decision = GatewayUsagePolicyEvaluator.evaluate(tokensOnly(1000L), withTokens(0L, 0L, 1001L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.violations()).containsExactly(GatewayUsagePolicyViolation.TOKENS_PER_DAY);
    }

    @Test
    void unknownTokensAreUsageUnknownNotLimitExceeded() {
        // Unknown must never be read as zero, and never as excess either.
        var decision = GatewayUsagePolicyEvaluator.evaluate(tokensOnly(1000L), requests(0L, 0L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.USAGE_UNKNOWN);
        assertThat(decision.isLimitExceeded()).isFalse();
        assertThat(decision.violations()).isEmpty();
        assertThat(decision.tokenUsageUnknown()).isTrue();
    }


    @Test
    void multipleLimitsWithNoViolationAllow() {
        GatewayUsagePolicy policy = new GatewayUsagePolicy("actor-1", "all", null, 10L, 100L, 1000L, true);

        var decision = GatewayUsagePolicyEvaluator.evaluate(policy, withTokens(9L, 99L, 999L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
        assertThat(decision.violations()).isEmpty();
    }

    @Test
    void multipleLimitsWithOneViolationReportExactlyThatOne() {
        GatewayUsagePolicy policy = new GatewayUsagePolicy("actor-1", "all", null, 10L, 100L, 1000L, true);

        var decision = GatewayUsagePolicyEvaluator.evaluate(policy, withTokens(11L, 99L, 999L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.violations()).containsExactly(GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE);
    }

    @Test
    void multipleViolationsAreAllReportedInDeclarationOrder() {
        // Every configured limit is examined, and the result is a stable list
        // rather than anything order-dependent.
        GatewayUsagePolicy policy = new GatewayUsagePolicy("actor-1", "all", null, 10L, 100L, 1000L, true);

        var decision = GatewayUsagePolicyEvaluator.evaluate(policy, withTokens(11L, 101L, 1001L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.violations()).containsExactly(
                GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE,
                GatewayUsagePolicyViolation.REQUESTS_PER_DAY,
                GatewayUsagePolicyViolation.TOKENS_PER_DAY);
    }

    @Test
    void violationOrderIsStableAcrossRuns() {
        GatewayUsagePolicy policy = new GatewayUsagePolicy("actor-1", "all", null, 10L, 100L, 1000L, true);
        GatewayUsagePolicyUsageSnapshot snapshot = withTokens(11L, 101L, 1001L);

        var first = GatewayUsagePolicyEvaluator.evaluate(policy, snapshot);
        var second = GatewayUsagePolicyEvaluator.evaluate(policy, snapshot);

        assertThat(first.violations()).isEqualTo(second.violations());
        assertThat(first.violations()).containsExactly(
                GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE,
                GatewayUsagePolicyViolation.REQUESTS_PER_DAY,
                GatewayUsagePolicyViolation.TOKENS_PER_DAY);
    }

    @Test
    void aDefiniteRequestViolationOutranksUnknownTokens() {
        // The request limit is definitively broken, so the decision is
        // LIMIT_EXCEEDED — but the token uncertainty is not lost.
        GatewayUsagePolicy policy = new GatewayUsagePolicy("actor-1", "all", null, 10L, 100L, 1000L, true);

        var decision = GatewayUsagePolicyEvaluator.evaluate(policy, requests(11L, 50L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.violations()).containsExactly(GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE);
        assertThat(decision.tokenUsageUnknown()).isTrue();
    }

    @Test
    void unknownTokensAreIrrelevantWithoutATokenLimit() {
        // No token limit configured means the token total is never consulted.
        var decision = GatewayUsagePolicyEvaluator.evaluate(minuteOnly(10L), requests(1L, 1L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
        assertThat(decision.tokenUsageUnknown()).isFalse();
    }

    @Test
    void eachNullLimitIsIgnored() {
        // Only requestsPerDay is configured, so enormous per-minute and token
        // values must not produce violations of the two unset limits.
        var decision = GatewayUsagePolicyEvaluator.evaluate(dayOnly(100L), withTokens(999_999L, 101L, 999_999L));

        assertThat(decision.violations()).containsExactly(GatewayUsagePolicyViolation.REQUESTS_PER_DAY);
    }

    @Test
    void aNullTokenLimitIgnoresAnUnknownTotal() {
        // The token limit is unset, so unknown token usage is not even a state.
        var decision = GatewayUsagePolicyEvaluator.evaluate(dayOnly(100L), requests(999_999L, 10L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.ALLOW);
        assertThat(decision.tokenUsageUnknown()).isFalse();
    }

    @Test
    void disabledPolicyIsInactiveAndNotSilentlyAllowed() {
        GatewayUsagePolicy disabled = new GatewayUsagePolicy("actor-1", "off", null, 1L, 1L, 1L, false);

        // Wildly over every limit, yet a disabled policy is not evaluated.
        var decision = GatewayUsagePolicyEvaluator.evaluate(disabled, withTokens(500L, 500L, 500L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.INACTIVE);
        assertThat(decision.isLimitExceeded()).isFalse();
        assertThat(decision.violations()).isEmpty();
        assertThat(decision.tokenUsageUnknown()).isFalse();
    }

    @Test
    void disabledPolicyIsInactiveEvenWithinLimits() {
        GatewayUsagePolicy disabled = new GatewayUsagePolicy("actor-1", "off", null, 10L, null, null, false);

        var decision = GatewayUsagePolicyEvaluator.evaluate(disabled, requests(0L, 0L));

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.INACTIVE);
    }

    @Test
    void nullPolicyIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> GatewayUsagePolicyEvaluator.evaluate(null, requests(0L, 0L)))
                .withMessage("policy must not be null");
    }

    @Test
    void nullSnapshotIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> GatewayUsagePolicyEvaluator.evaluate(minuteOnly(10L), null))
                .withMessage("snapshot must not be null");
    }

    @Test
    void negativeRequestCountsAreRejected() {
        assertThatThrownBy(() -> GatewayUsagePolicyUsageSnapshot.withoutTokenUsage(-1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestsInCurrentMinute must not be negative");
        assertThatThrownBy(() -> GatewayUsagePolicyUsageSnapshot.withoutTokenUsage(0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestsInCurrentDay must not be negative");
    }

    @Test
    void negativeTokenCountIsRejected() {
        assertThatThrownBy(() -> GatewayUsagePolicyUsageSnapshot.withTokenUsage(0L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalTokensInCurrentDay must not be negative");
    }

    @Test
    void claimingAKnownTotalWhileSupplyingNoneIsRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicyUsageSnapshot(0L, 0L, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalTokensInCurrentDay is required when tokenUsageKnown is true");
    }

    @Test
    void theEvaluatorIsPureAndUnwired() {
        // Not a Spring bean, no fields, and no route into the gateway flow.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEvaluator.class.getDeclaredFields())
                        .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                        .count())
                .isZero();
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyEvaluator.class.getAnnotations())
                        .map(annotation -> annotation.annotationType().getName())
                        .toList())
                .isEmpty();
        assertThat(java.util.Arrays.stream(java.lang.reflect.Modifier.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getName))
                .contains("ABSTRACT", "FINAL", "STATIC");
    }

    @Test
    void theDecisionModelCarriesNoMonetaryConcepts() {
        // No pricing, cost, budget currency, utilization, or billing fields.
        var fieldNames = java.util.stream.Stream.of(
                        GatewayUsagePolicyDecision.class.getRecordComponents(),
                        GatewayUsagePolicyUsageSnapshot.class.getRecordComponents())
                .flatMap(java.util.Arrays::stream)
                .map(java.lang.reflect.RecordComponent::getName)
                .map(String::toLowerCase)
                .toList();

        assertThat(fieldNames)
                .noneMatch(name -> name.contains("cost")
                        || name.contains("price")
                        || name.contains("currency")
                        || name.contains("budget")
                        || name.contains("utilization")
                        || name.contains("percent"));
        assertThat(GatewayUsagePolicyDecision.State.values())
                .containsExactlyInAnyOrder(
                        GatewayUsagePolicyDecision.State.ALLOW,
                        GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED,
                        GatewayUsagePolicyDecision.State.USAGE_UNKNOWN,
                        GatewayUsagePolicyDecision.State.INACTIVE);
        assertThat(List.of(GatewayUsagePolicyViolation.values()))
                .containsExactly(
                        GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE,
                        GatewayUsagePolicyViolation.REQUESTS_PER_DAY,
                        GatewayUsagePolicyViolation.TOKENS_PER_DAY);
    }
}
