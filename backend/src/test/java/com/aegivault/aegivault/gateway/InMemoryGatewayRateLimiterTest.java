package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link InMemoryGatewayRateLimiter} (no Spring
 * context, no I/O): fixed-window boundaries, per-actor isolation,
 * deterministic expiry through a mutable clock (never
 * {@code Thread.sleep}), lazy eviction of idle actors, and atomicity
 * under concurrent attempts.
 */
class InMemoryGatewayRateLimiterTest {

    /** Minimal mutable clock so window expiry is deterministic. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private static MutableClock clock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void requestsBelowTheLimitAreAllowed() {
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(clock());

        for (int i = 0; i < InMemoryGatewayRateLimiter.MAX_REQUESTS - 1; i++) {
            assertThat(limiter.tryAcquire("actor-1")).isTrue();
        }
    }

    @Test
    void exactlyTheTwentiethRequestIsAllowedAndTheTwentyFirstIsNot() {
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(clock());

        for (int i = 1; i <= InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            assertThat(limiter.tryAcquire("actor-1")).as("request %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();
    }

    @Test
    void rejectedActorStaysRejectedForTheRestOfTheWindow() {
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(clock());
        for (int i = 0; i < InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            limiter.tryAcquire("actor-1");
        }

        assertThat(limiter.tryAcquire("actor-1")).isFalse();
        assertThat(limiter.tryAcquire("actor-1")).isFalse();
    }

    @Test
    void separateActorsHaveIndependentQuotas() {
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(clock());
        for (int i = 0; i < InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            assertThat(limiter.tryAcquire("actor-1")).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();

        for (int i = 1; i <= InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            assertThat(limiter.tryAcquire("actor-2")).as("actor-2 request %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-2")).isFalse();
    }

    @Test
    void quotaResetsAfterTheWindowExpires() {
        MutableClock time = clock();
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(time);
        for (int i = 0; i < InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            limiter.tryAcquire("actor-1");
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();

        time.advance(InMemoryGatewayRateLimiter.WINDOW.plusSeconds(1));

        for (int i = 1; i <= InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            assertThat(limiter.tryAcquire("actor-1")).as("next-window request %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();
    }

    @Test
    void attemptsJustBeforeTheBoundaryStillCountAgainstTheOldWindow() {
        MutableClock time = clock();
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(time);
        for (int i = 0; i < InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            limiter.tryAcquire("actor-1");
        }

        time.advance(InMemoryGatewayRateLimiter.WINDOW.minusSeconds(1));

        assertThat(limiter.tryAcquire("actor-1")).isFalse();
    }

    @Test
    void blankActorsAreRejected() {
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(clock());

        assertThatThrownBy(() -> limiter.tryAcquire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyValuesStayCentralized() {
        assertThat(InMemoryGatewayRateLimiter.MAX_REQUESTS).isEqualTo(20);
        assertThat(InMemoryGatewayRateLimiter.WINDOW).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void idleActorsAreEvictedInsteadOfAccumulating() {
        MutableClock time = clock();
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(time);
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("idle-actor-" + i);
        }

        time.advance(InMemoryGatewayRateLimiter.WINDOW.plusSeconds(1));
        limiter.tryAcquire("fresh-actor");

        assertThat(limiter.trackedActors()).containsExactly("fresh-actor");
    }

    @Test
    void concurrentAttemptsCannotOvershootTheLimit() throws Exception {
        InMemoryGatewayRateLimiter limiter = new InMemoryGatewayRateLimiter(clock());
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (limiter.tryAcquire("actor-1")) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(allowed.get()).isEqualTo(InMemoryGatewayRateLimiter.MAX_REQUESTS);
            assertThat(limiter.tryAcquire("actor-1")).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
