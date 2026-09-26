package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.gateway.usage.GatewayUsageDayWindow;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyUsageSnapshotProvider} (no
 * Spring context, no database). They pin the UTC window arithmetic, the
 * input validation, and — most importantly — the token-known rule: a day
 * with any unknown provider total must report unknown rather than a
 * partial sum.
 */
class GatewayUsagePolicyUsageSnapshotProviderTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private final GatewayUsageRepository usage = mock(GatewayUsageRepository.class);

    private final GatewayUsagePolicyUsageSnapshotProvider provider =
            new GatewayUsagePolicyUsageSnapshotProvider(usage);

    private static GatewayUsageDayWindow day(long recordCount, Long totalTokens, long knownTokenCount) {
        return new GatewayUsageDayWindow() {
            @Override
            public long getRecordCount() {
                return recordCount;
            }

            @Override
            public Long getTotalTokens() {
                return totalTokens;
            }

            @Override
            public long getKnownTokenCount() {
                return knownTokenCount;
            }
        };
    }

    private void stub(long minuteCount, GatewayUsageDayWindow day) {
        when(usage.countByActorSubjectAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(minuteCount);
        when(usage.summarizeDayWindow(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(day);
    }

    @Test
    void anEmptyDayIsZeroCountsAndAKnownZeroTokenTotal() {
        stub(0L, day(0L, null, 0L));

        var snapshot = provider.snapshotFor("actor-1", NOW);

        assertThat(snapshot.requestsInCurrentMinute()).isZero();
        assertThat(snapshot.requestsInCurrentDay()).isZero();
        assertThat(snapshot.totalTokensInCurrentDay()).isZero();
        assertThat(snapshot.tokenUsageKnown()).isTrue();
    }

    @Test
    void aFullyKnownDayReportsTheSummedTokenTotal() {
        stub(4L, day(9L, 1234L, 9L));

        var snapshot = provider.snapshotFor("actor-1", NOW);

        assertThat(snapshot.requestsInCurrentMinute()).isEqualTo(4L);
        assertThat(snapshot.requestsInCurrentDay()).isEqualTo(9L);
        assertThat(snapshot.totalTokensInCurrentDay()).isEqualTo(1234L);
        assertThat(snapshot.tokenUsageKnown()).isTrue();
    }

    @Test
    void oneUnknownTotalMakesTheWholeDayUnknown() {
        // The database still returns the partial sum, but the known count is
        // lower than the row count, so the partial sum must be discarded.
        stub(2L, day(5L, 400L, 4L));

        var snapshot = provider.snapshotFor("actor-1", NOW);

        assertThat(snapshot.requestsInCurrentDay()).isEqualTo(5L);
        assertThat(snapshot.totalTokensInCurrentDay()).isNull();
        assertThat(snapshot.tokenUsageKnown()).isFalse();
    }

    @Test
    void aDayWithNoKnownTotalsAtAllIsUnknownNotZero() {
        stub(0L, day(3L, null, 0L));

        var snapshot = provider.snapshotFor("actor-1", NOW);

        assertThat(snapshot.requestsInCurrentDay()).isEqualTo(3L);
        assertThat(snapshot.totalTokensInCurrentDay()).isNull();
        assertThat(snapshot.tokenUsageKnown()).isFalse();
    }

    @Test
    void theMinuteAndDayWindowsAreHalfOpenUtcRanges() {
        stub(0L, day(0L, null, 0L));

        provider.snapshotFor("actor-1", NOW);

        // 12:30:45.123Z falls in minute [12:30:00, 12:31:00) and in day
        // [2026-03-15T00:00:00Z, 2026-03-16T00:00:00Z), both in UTC.
        verify(usage).countByActorSubjectAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                "actor-1",
                Instant.parse("2026-03-15T12:30:00Z"),
                Instant.parse("2026-03-15T12:31:00Z"));
        verify(usage).summarizeDayWindow(
                "actor-1",
                Instant.parse("2026-03-15T00:00:00Z"),
                Instant.parse("2026-03-16T00:00:00Z"));
    }

    @Test
    void windowsAreComputedInUtcNotTheJvmDefaultZone() {
        stub(0L, day(0L, null, 0L));

        // Just after midnight UTC: the day must roll over even when the JVM
        // default zone would place it on the previous or next calendar day.
        provider.snapshotFor("actor-1", Instant.parse("2026-03-15T00:00:00Z"));

        verify(usage).summarizeDayWindow(
                "actor-1",
                Instant.parse("2026-03-15T00:00:00Z"),
                Instant.parse("2026-03-16T00:00:00Z"));
    }

    @Test
    void aMidnightInstantStillSeesTheNewUtcDay() {
        stub(0L, day(0L, null, 0L));

        provider.snapshotFor("actor-1", Instant.parse("2026-03-16T00:00:00Z"));

        verify(usage).summarizeDayWindow(
                "actor-1",
                Instant.parse("2026-03-16T00:00:00Z"),
                Instant.parse("2026-03-17T00:00:00Z"));
    }

    @Test
    void actorSubjectIsTrimmedBeforeTheQueries() {
        stub(0L, day(0L, null, 0L));

        provider.snapshotFor("  actor-1  ", NOW);

        verify(usage).countByActorSubjectAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                org.mockito.ArgumentMatchers.eq("actor-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(usage).summarizeDayWindow(
                org.mockito.ArgumentMatchers.eq("actor-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankActorIsRejectedWithoutAnyQuery() {
        assertThatThrownBy(() -> provider.snapshotFor("   ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoMoreInteractions(usage);
    }

    @Test
    void nullActorIsRejectedWithoutAnyQuery() {
        assertThatThrownBy(() -> provider.snapshotFor(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoMoreInteractions(usage);
    }

    @Test
    void nullNowIsRejectedWithoutAnyQuery() {
        assertThatThrownBy(() -> provider.snapshotFor("actor-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("now must not be null");
        verifyNoMoreInteractions(usage);
    }

    @Test
    void theProviderIsReadOnlyOverTheUsageRepository() {
        // It reads two aggregates and nothing else: no writes, no counters,
        // and no path toward the gateway traffic flow.
        var dependencies = java.util.Arrays.stream(
                        GatewayUsagePolicyUsageSnapshotProvider.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies).containsExactly(GatewayUsageRepository.class.getName());
    }

    @Test
    void theSnapshotItProducesFeedsTheEvaluatorUnchanged() {
        // 11 requests this minute against a limit of 5, and 1000 tokens today
        // against a limit of 500: both limits are genuinely exceeded.
        stub(11L, day(12L, 1000L, 12L));

        GatewayUsagePolicyUsageSnapshot snapshot = provider.snapshotFor("actor-1", NOW);
        GatewayUsagePolicy policy =
                new GatewayUsagePolicy("actor-1", "strict", null, 5L, null, 500L, true);

        // The provider and evaluator compose without any glue object.
        var decision = GatewayUsagePolicyEvaluator.evaluate(policy, snapshot);

        assertThat(decision.state()).isEqualTo(GatewayUsagePolicyDecision.State.LIMIT_EXCEEDED);
        assertThat(decision.violations()).containsExactly(
                GatewayUsagePolicyViolation.REQUESTS_PER_MINUTE,
                GatewayUsagePolicyViolation.TOKENS_PER_DAY);
    }
}
