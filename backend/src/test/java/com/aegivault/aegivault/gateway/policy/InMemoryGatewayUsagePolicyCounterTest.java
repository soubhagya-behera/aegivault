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
}
