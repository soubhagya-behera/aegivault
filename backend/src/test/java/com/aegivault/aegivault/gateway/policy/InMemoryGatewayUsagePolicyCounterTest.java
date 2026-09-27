package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link InMemoryGatewayUsagePolicyCounter} (no Spring
 * context, no database, no Redis, no gateway wiring). They pin the admission
 * semantics at and around the limit, per-actor and per-window isolation,
 * window rollover, the concurrency guarantee, and input validation. Time is
 * supplied by a mutable clock, so nothing here sleeps or depends on the wall
 * clock.
 */
class InMemoryGatewayUsagePolicyCounterTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private MutableClock clock;

    private InMemoryGatewayUsagePolicyCounter counter;

    /** A clock the test moves by hand, so windows change deterministically. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        counter = new InMemoryGatewayUsagePolicyCounter(clock);
    }

    private boolean consume(String actor, GatewayUsagePolicyCounterWindow window, long limit) {
        return counter.tryConsume(actor, window, limit);
    }

    /** An atomic two-window request: one unit from DAY and one from MINUTE. */
    private GatewayUsagePolicyCounterResult both(long perDay, long perMinute) {
        return both("actor-1", perDay, perMinute);
    }

    private GatewayUsagePolicyCounterResult both(String actor, long perDay, long perMinute) {
        // Uses the mutable clock, like consume(...), so a window rollover is
        // observable exactly as it would be in production.
        return counter.tryConsume(
                GatewayUsagePolicyCounterRequest.ofBoth(actor, clock.instant(), perDay, perMinute));
    }

    @Test
    void firstRequestIsAllowed() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L)).isTrue();
    }

    @Test
    void aLimitOfOneAllowsTheFirstRequestAndRejectsTheSecond() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
    }

    @Test
    void exactlyAtTheLimitIsStillAllowedAndTheNextRequestIsRejected() {
        // A limit is the highest permitted value, so request 5 of 5 passes.
        for (int i = 1; i <= 5; i++) {
            assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                    .as("request %d of 5", i)
                    .isTrue();
        }
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .as("request 6 exceeds the limit of 5")
                .isFalse();
    }

    @Test
    void rejectedRequestsDoNotConsumeFurtherQuota() {
        // A rejected attempt must not increment: the count stays equal to the
        // number of admitted requests, so hammering a spent limit cannot make
        // the situation worse or push the counter past the limit.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 2L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 2L)).isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 2L)).isFalse();
        }
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 2L)).isFalse();
    }

    @Test
    void separateActorsHaveIndependentCounters() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        // Actor 2 is untouched by actor 1 spending its own limit.
        assertThat(consume("actor-2", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
        assertThat(consume("actor-2", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
    }

    @Test
    void minuteAndDayWindowsCountIndependently() {
        // Exhausting the minute limit must not affect the day window, even
        // though both are keyed by the same actor at the same instant.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();

        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 5L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 5L)).isTrue();
    }

    @Test
    void aNewMinuteWindowStartsFromZeroAgain() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();

        clock.advance(Duration.ofSeconds(15));

        // The old window is over: the counter is a fresh one, not the spent
        // one, so the actor is admitted again.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
    }

    @Test
    void aNewDayWindowStartsFromZeroAgain() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isFalse();

        // Across the UTC midnight boundary.
        clock.advance(Duration.ofDays(1));

        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
    }

    @Test
    void theSameInstantAlwaysProducesTheSameWindow() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        // Time has not moved: the actor stays in the same window and stays
        // rejected, proving windows come from the clock and not from the
        // order in which requests happen to arrive.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
    }

    @Test
    void concurrentRequestsCannotExceedTheLimit() throws Exception {
        // The real regression this abstraction exists to prevent: many
        // simultaneous requests must not each read the same count and all be
        // admitted. Exactly `limit` may pass.
        int limit = 20;
        int threads = 60;
        InMemoryGatewayUsagePolicyCounter concurrent = new InMemoryGatewayUsagePolicyCounter(clock);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (concurrent.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, limit)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(allowed.get())
                    .as("exactly the limit may be admitted, no more and no fewer")
                    .isEqualTo(limit);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentActorsEachKeepTheirOwnQuota() throws Exception {
        int threads = 30;
        InMemoryGatewayUsagePolicyCounter concurrent = new InMemoryGatewayUsagePolicyCounter(clock);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                String actor = "actor-" + (i % 3);
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (concurrent.tryConsume(actor, GatewayUsagePolicyCounterWindow.DAY, 2L)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);

            // Three actors, two requests each: sharing one counter across
            // actors would let one actor spend another's quota.
            assertThat(terminated).isTrue();
            assertThat(allowed.get()).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aBlankActorIsRejectedBeforeAnyStateIsCreated() {
        assertThatThrownBy(() -> consume(null, GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> consume("   ", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
    }

    @Test
    void aNullWindowIsRejected() {
        assertThatThrownBy(() -> consume("actor-1", null, 5L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("window must not be null");
    }

    @Test
    void anInvalidLimitIsRejected() {
        // A zero or negative limit is a contradiction, not a limit: no
        // request could ever satisfy "current + 1 <= 0", so accepting it
        // would quietly mean "deny everything".
        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }

    @Test
    void theActorIsTrimmedLikeTheRestOfThePolicyPackage() {
        assertThat(consume("  actor-1  ", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        // The trimmed subject is the one counted, so an untrimmed spelling of
        // the same actor cannot start a second quota.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
    }

    @Test
    void aNullClockIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new InMemoryGatewayUsagePolicyCounter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void theCounterSharesNoStateOrTypeWithTheGlobalRateLimiter() {
        // Policy enforcement state and the global gateway rate-limit state are
        // separate concerns: a policy counter must never hold or reuse the
        // rate limiter's counters, and the two must be independently
        // disposable.
        assertThat(GatewayUsagePolicyCounter.class).isNotEqualTo(com.aegivault.aegivault.gateway.GatewayRateLimiter.class);
        assertThat(GatewayUsagePolicyCounterWindow.KEY_PREFIX)
                .as("policy keys must never collide with rate-limit keys")
                .isNotEqualTo(com.aegivault.aegivault.gateway.GatewayRateLimitPolicy.KEY_PREFIX)
                .doesNotContain("rate-limit");
    }

    @Test
    void bothWindowsAvailableIncrementsBoth() {
        var result = both(10L, 2L);

        assertThat(result.isAllowed()).isTrue();

        // Both counters advanced, proven by each limit being hit exactly one
        // attempt later.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 10L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 2L)).isTrue();
    }

    @Test
    void aMinuteRejectionLeavesTheDayCountUntouched() {
        // Exhaust only the minute window, leaving day capacity to spare.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();

        var result = both(100L, 1L);

        // REJECTED(MINUTE) with the day still at zero: the whole point of the
        // atomic operation is that a refused request costs nothing.
        assertThat(result.state()).isEqualTo(GatewayUsagePolicyCounterResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);

        // Day capacity is untouched: all 100 day-only requests still fit.
        for (int i = 0; i < 100; i++) {
            assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L))
                    .as("day request %d", i + 1)
                    .isTrue();
        }
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L)).isFalse();
    }

    @Test
    void aDayRejectionLeavesTheMinuteCountUntouched() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isFalse();

        var result = both(1L, 100L);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyCounterResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);

        // The minute window was never charged for the refused request.
        for (int i = 0; i < 100; i++) {
            assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 100L))
                    .as("minute request %d", i + 1)
                    .isTrue();
        }
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 100L)).isFalse();
    }

    @Test
    void bothExhaustedReportsTheDayWindowDeterministically() {
        // Exhaust both windows, then ask again. The reported window is DAY
        // because the evaluation order is fixed, never derived from limit
        // values or arrival order.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        var result = both(1L, 1L);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyCounterResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
    }

    @Test
    void theExhaustedWindowIsReportedTheSameWayRegardlessOfWhichWentFirst() {
        // Exhausting minute-then-day and day-then-minute both report DAY,
        // proving the order is fixed rather than arrival-dependent.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
        assertThat(both(1L, 1L).rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);

        assertThat(consume("actor-2", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
        assertThat(consume("actor-2", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(both("actor-2", 1L, 1L).rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
    }

    @Test
    void aSingleWindowRequestKeepsTheSingleWindowBoundary() {
        // The multi-window path must not change the single-window semantics:
        // the last permitted attempt passes, the next is refused, and the
        // refusal consumes nothing.
        var request = GatewayUsagePolicyCounterRequest.of(
                "actor-1", NOW, GatewayUsagePolicyCounterWindow.MINUTE, 2L);

        assertThat(counter.tryConsume(request).isAllowed()).isTrue();
        assertThat(counter.tryConsume(request).isAllowed()).isTrue();
        assertThat(counter.tryConsume(request).isAllowed()).isFalse();
        assertThat(counter.tryConsume(request).rejectedWindow())
                .isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
    }

    @Test
    void separateActorsStayIsolatedAcrossAMultiWindowAttempt() {
        assertThat(both("actor-1", 1L, 1L).isAllowed()).isTrue();
        assertThat(both("actor-1", 1L, 1L).isAllowed()).isFalse();

        // actor-2 starts fresh in both windows: one actor spending its
        // capacity must never affect another.
        assertThat(both("actor-2", 1L, 1L).isAllowed()).isTrue();
        assertThat(both("actor-2", 1L, 1L).isAllowed()).isFalse();
    }

    @Test
    void aNewMinuteWindowResetsOnlyTheMinuteCounter() {
        assertThat(both(100L, 1L).isAllowed()).isTrue();
        assertThat(both(100L, 1L).isAllowed()).isFalse();

        clock.advance(Duration.ofSeconds(30));

        // The minute window rolled over but the day window has not, so the
        // actor is admitted again on minute capacity while the day count keeps
        // accumulating.
        assertThat(both(100L, 1L).isAllowed()).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L)).isTrue();
    }

    @Test
    void concurrentMultiWindowAttemptsCannotExceedEitherLimit() throws Exception {
        // The regression this contract upgrade prevents: interleaved
        // two-window attempts must not admit more requests than the tighter
        // limit allows, and must never half-apply.
        int dayLimit = 50;
        int minuteLimit = 8;
        int threads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (counter.tryConsume(
                                    GatewayUsagePolicyCounterRequest.ofBoth(
                                            "actor-1", clock.instant(), dayLimit, minuteLimit))
                            .isAllowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);

            // The minute limit is the binding one and is never exceeded.
            assertThat(terminated).isTrue();
            assertThat(allowed.get())
                    .as("the tighter minute limit bounds admissions")
                    .isEqualTo(minuteLimit);
        } finally {
            pool.shutdownNow();
        }

        // The day counter was charged for exactly the admitted requests and
        // no more, so no partial applies drifted it.
        for (int i = 0; i < dayLimit - minuteLimit; i++) {
            assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, dayLimit))
                    .as("remaining day capacity %d", i + 1)
                    .isTrue();
        }
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, dayLimit)).isFalse();
    }

    @Test
    void rejectedMultiWindowAttemptsChargeNothingUnderConcurrency() throws Exception {
        // Exhaust the minute window, then hammer with two-window attempts.
        // The day counter must stay exactly where it was, proving rejections
        // never partially apply even under contention.
        assertThat(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (!counter.tryConsume(
                                    GatewayUsagePolicyCounterRequest.ofBoth(
                                            "actor-1", clock.instant(), 1_000L, 1L))
                            .isAllowed()) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(rejected.get()).isEqualTo(threads);
        } finally {
            pool.shutdownNow();
        }

        // The day window is still completely unspent: all 1000 day-only
        // requests fit, so not one of the 40 rejections charged it.
        for (int i = 0; i < 1_000; i++) {
            assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1_000L))
                    .as("day request %d", i + 1)
                    .isTrue();
        }
    }
}
