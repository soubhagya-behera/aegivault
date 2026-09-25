package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pure unit tests for {@link RedisGatewayRateLimiter} with a Mockito
 * {@link StringRedisTemplate} — no Spring context, no Redis server, no
 * network. A recording answer stands in for the Lua execution: it
 * captures keys and arguments and counts like the script's INCR would,
 * so window boundaries, key isolation, single-call atomicity, failure
 * handling, and concurrent attempts are all proven without a server.
 */
class RedisGatewayRateLimiterTest {

    private StringRedisTemplate redis;

    private final List<List<String>> keysSeen = Collections.synchronizedList(new ArrayList<>());

    private final List<Object[]> argsSeen = Collections.synchronizedList(new ArrayList<>());

    private final AtomicLong serverCounter = new AtomicLong();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void scriptCountsLikeRedisWould() {
        redis = mock(StringRedisTemplate.class);
        keysSeen.clear();
        argsSeen.clear();
        serverCounter.set(0);
        Answer<Long> fixedWindow = invocation -> {
            keysSeen.add(List.copyOf((List<String>) invocation.getArgument(1)));
            argsSeen.add(new Object[]{invocation.getArgument(2), invocation.getArgument(3)});
            long current = serverCounter.incrementAndGet();
            return current <= GatewayRateLimitPolicy.MAX_REQUESTS ? 1L : 0L;
        };
        // Four matchers: Mockito matches the two trailing script arguments
        // as expanded varargs, and production always passes exactly two.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(fixedWindow);
    }

    private RedisGatewayRateLimiter limiter() {
        return new RedisGatewayRateLimiter(redis);
    }

    @Test
    void firstRequestIsAllowed() {
        assertThat(limiter().tryAcquire("actor-1")).isTrue();
    }

    @Test
    void twentiethRequestIsAllowedAndTwentyFirstIsRejected() {
        RedisGatewayRateLimiter limiter = limiter();

        for (int i = 1; i <= GatewayRateLimitPolicy.MAX_REQUESTS; i++) {
            assertThat(limiter.tryAcquire("actor-1")).as("request %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();
        assertThat(limiter.tryAcquire("actor-1")).isFalse();
    }

    @Test
    void separateActorsHaveIndependentCounters() {
        RedisGatewayRateLimiter limiter = limiter();
        for (int i = 0; i < GatewayRateLimitPolicy.MAX_REQUESTS; i++) {
            limiter.tryAcquire("actor-1");
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();

        serverCounter.set(0);
        keysSeen.clear();
        for (int i = 1; i <= GatewayRateLimitPolicy.MAX_REQUESTS; i++) {
            assertThat(limiter.tryAcquire("actor-2")).as("actor-2 request %d", i).isTrue();
        }

        assertThat(keysSeen).containsExactly(
                Collections.nCopies(GatewayRateLimitPolicy.MAX_REQUESTS,
                        List.of("aegivault:gateway:rate-limit:actor-2")).toArray(new List[0]));
    }

    @Test
    void keyUsesTheDocumentedNamespaceAndSubjectOnly() {
        limiter().tryAcquire("actor-1");

        assertThat(keysSeen).containsExactly(List.of("aegivault:gateway:rate-limit:actor-1"));
        assertThat(GatewayRateLimitPolicy.keyFor("actor-1")).isEqualTo("aegivault:gateway:rate-limit:actor-1");
    }

    @Test
    void scriptReceivesTheCentralizedPolicyValues() {
        limiter().tryAcquire("actor-1");

        assertThat(argsSeen).hasSize(1);
        assertThat(argsSeen.get(0)).containsExactly(
                String.valueOf(GatewayRateLimitPolicy.MAX_REQUESTS),
                String.valueOf(GatewayRateLimitPolicy.windowMillis()));
        assertThat(GatewayRateLimitPolicy.MAX_REQUESTS).isEqualTo(20);
        assertThat(GatewayRateLimitPolicy.windowMillis()).isEqualTo(60_000L);
    }

    @Test
    void eachAttemptIsExactlyOneAtomicScriptExecution() {
        RedisGatewayRateLimiter limiter = limiter();
        limiter.tryAcquire("actor-1");
        limiter.tryAcquire("actor-1");
        limiter.tryAcquire("actor-2");

        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(), any());
        String script = RedisGatewayRateLimiter.FIXED_WINDOW_SCRIPT.getScriptAsString();
        assertThat(script).contains("INCR").contains("PEXPIRE");
    }

    @Test
    void newWindowAllowsAgainAfterExpiry() {
        RedisGatewayRateLimiter limiter = limiter();
        for (int i = 0; i < GatewayRateLimitPolicy.MAX_REQUESTS; i++) {
            limiter.tryAcquire("actor-1");
        }
        assertThat(limiter.tryAcquire("actor-1")).isFalse();

        // The old key expired server-side: the counter restarts at one.
        serverCounter.set(0);

        assertThat(limiter.tryAcquire("actor-1")).isTrue();
    }

    @Test
    void redisFailureFailsClosedWithAGenericError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis-connection-refused-9z"));

        assertThatThrownBy(() -> limiter().tryAcquire("actor-1"))
                .isInstanceOf(GatewayRateLimitUnavailableException.class)
                .hasMessage(GatewayRateLimitUnavailableException.MESSAGE)
                .hasMessage("Unable to check gateway rate limit.")
                .hasMessageNotContaining("redis-connection-refused-9z");
        assertThat(GatewayRateLimitUnavailableException.MESSAGE)
                .doesNotContain("localhost", "6379", "INCR", "actor-1");
    }

    @Test
    void emptyScriptResultFailsClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> limiter().tryAcquire("actor-1"))
                .isInstanceOf(GatewayRateLimitUnavailableException.class)
                .hasMessage(GatewayRateLimitUnavailableException.MESSAGE);
    }

    @Test
    void blankActorsAreRejectedWithoutTouchingRedis() {
        RedisGatewayRateLimiter limiter = limiter();

        assertThatThrownBy(() -> limiter.tryAcquire(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("  ")).isInstanceOf(IllegalArgumentException.class);
        verify(redis, times(0)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void concurrentAttemptsFollowTheSingleSharedCounter() throws Exception {
        RedisGatewayRateLimiter limiter = limiter();
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
            boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(allowed.get()).isEqualTo(GatewayRateLimitPolicy.MAX_REQUESTS);
            verify(redis, times(threads)).execute(any(RedisScript.class), anyList(), any(), any());
        } finally {
            pool.shutdownNow();
        }
    }
}
