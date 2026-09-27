package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pure unit tests for {@link RedisGatewayUsagePolicyCounter} with a Mockito
 * {@link StringRedisTemplate} — no Spring context, no Redis server, no
 * network, following the approach the existing Redis rate-limiter test
 * already uses. A recording answer stands in for the Lua execution: it
 * captures the key and arguments and counts per key exactly as the script's
 * INCR would, so key format, per-window isolation, single-call atomicity,
 * TTL arguments, and fail-closed behavior are all proven without a server.
 */
class RedisGatewayUsagePolicyCounterTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private StringRedisTemplate redis;

    private final List<List<String>> keysSeen = Collections.synchronizedList(new ArrayList<>());

    private final List<Object[]> argsSeen = Collections.synchronizedList(new ArrayList<>());

    /** Stands in for the server-side counters, one per Redis key. */
    private final Map<String, AtomicLong> serverCounters = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void scriptCountsLikeRedisWould() {
        redis = mock(StringRedisTemplate.class);
        keysSeen.clear();
        argsSeen.clear();
        serverCounters.clear();
        Answer<Long> tryConsume = (InvocationOnMock invocation) -> {
            List<String> keys = List.copyOf((List<String>) invocation.getArgument(1));
            String limit = (String) invocation.getArgument(2);
            keysSeen.add(keys);
            argsSeen.add(new Object[] {invocation.getArgument(2), invocation.getArgument(3)});
            long current = serverCounters
                    .computeIfAbsent(keys.get(0), key -> new AtomicLong())
                    .incrementAndGet();
            return current <= Long.parseLong(limit) ? 1L : 0L;
        };
        // Two matchers: Mockito matches the two trailing script arguments as
        // expanded varargs, and production always passes exactly two.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(tryConsume);
    }

    private RedisGatewayUsagePolicyCounter counter() {
        return new RedisGatewayUsagePolicyCounter(redis, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private boolean consume(String actor, GatewayUsagePolicyCounterWindow window, long limit) {
        return counter().tryConsume(actor, window, limit);
    }

    @Test
    void firstRequestIsAllowed() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L)).isTrue();
    }

    @Test
    void exactlyAtTheLimitIsAllowedAndTheNextRequestIsRejected() {
        RedisGatewayUsagePolicyCounter counter = counter();

        for (int i = 1; i <= 5; i++) {
            assertThat(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                    .as("request %d of 5", i)
                    .isTrue();
        }
        assertThat(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .as("request 6 exceeds the limit of 5")
                .isFalse();
    }

    @Test
    void eachConsumeIsExactlyOneAtomicScriptExecution() {
        // The whole point: one round trip per attempt, not GET + INCR +
        // EXPIRE. A separate read and write would leave a check-then-act gap
        // that two concurrent requests could both slip through.
        RedisGatewayUsagePolicyCounter counter = counter();
        counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);
        counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);
        counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);

        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(), any());

        // Increment, TTL, and the limit comparison all live inside the one
        // script; no separate INCR or EXPIRE command is ever issued.
        String script = RedisGatewayUsagePolicyCounter.TRY_CONSUME_SCRIPT.getScriptAsString();
        assertThat(script).contains("INCR").contains("PEXPIRE").contains("current <= tonumber(ARGV[1])");
        verifyNoMoreInteractions(redis);
    }

    @Test
    void theLimitIsPassedIntoTheAtomicOperation() {
        consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 7L);

        // The limit travels as a script argument, so the comparison happens
        // server-side against the value the caller asked for.
        assertThat(argsSeen).hasSize(1);
        assertThat(argsSeen.get(0)[0]).isEqualTo("7");
    }

    @Test
    void theKeyIsNamespacedByWindowAndWindowStart() {
        consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);
        consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 5L);

        // Deterministic documented format:
        // aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>
        assertThat(keysSeen).containsExactly(
                List.of("aegivault:gateway:policy-counter:minute:1773577800:actor-1"),
                List.of("aegivault:gateway:policy-counter:day:1773532800:actor-1"));
    }

    @Test
    void policyKeysCanNeverCollideWithTheGlobalRateLimiterKeys() {
        consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);

        String key = keysSeen.get(0).get(0);
        assertThat(key).startsWith(GatewayUsagePolicyCounterWindow.KEY_PREFIX);
        assertThat(key).doesNotContain("rate-limit");
        assertThat(key).isNotEqualTo("aegivault:gateway:rate-limit:actor-1");
    }

    @Test
    void minuteAndDayWindowsUseDifferentKeysAndIndependentCounters() {
        RedisGatewayUsagePolicyCounter counter = counter();
        counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L);
        assertThat(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();

        // A separate key per window: the exhausted minute quota does not
        // carry over into the day counter.
        assertThat(counter.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 5L)).isTrue();
        // keysSeen holds one entry per call, so the day call is the last one.
        assertThat(keysSeen.get(keysSeen.size() - 1).get(0))
                .isNotEqualTo(keysSeen.get(0).get(0))
                .contains("policy-counter:day:");
    }

    @Test
    void separateActorsUseDifferentKeys() {
        consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L);
        consume("actor-2", GatewayUsagePolicyCounterWindow.MINUTE, 1L);

        assertThat(keysSeen).containsExactly(
                List.of("aegivault:gateway:policy-counter:minute:1773577800:actor-1"),
                List.of("aegivault:gateway:policy-counter:minute:1773577800:actor-2"));
    }

    @Test
    void theKeyCarriesATtlThatOutlivesTheRestOfTheWindow() {
        // The key must expire by itself once its window ends — no cleanup job.
        // NOW is 12:30:45.123 into a minute that ends at 12:31:00, so ~14.877s
        // remain, plus a one-second clock-skew grace.
        consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);

        long ttl = Long.parseLong((String) argsSeen.get(0)[1]);
        long remainingInWindow = 15_000L - 123L;
        assertThat(ttl)
                .isEqualTo(remainingInWindow + GatewayUsagePolicyCounterWindow.EXPIRY_GRACE.toMillis())
                .isLessThanOrEqualTo(GatewayUsagePolicyCounterWindow.MINUTE.length().toMillis());
    }

    @Test
    void aDayWindowGetsATtlThatCoversTheRestOfThatDay() {
        consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 5L);

        long ttl = Long.parseLong((String) argsSeen.get(0)[1]);
        assertThat(ttl)
                .isGreaterThan(Duration.ofHours(11).toMillis())
                .isLessThanOrEqualTo(GatewayUsagePolicyCounterWindow.DAY.length().toMillis());
    }

    @Test
    void aNewWindowUsesANewKeyAndRestartsTheCount() {
        RedisGatewayUsagePolicyCounter first = counter();
        assertThat(first.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(first.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isFalse();
        String exhaustedWindowKey = keysSeen.get(0).get(0);

        // A minute later the window has rolled over. The new window start is
        // part of the key, so the counter is a new one rather than a reset of
        // the old one — no in-place mutation is needed on the server.
        RedisGatewayUsagePolicyCounter next = new RedisGatewayUsagePolicyCounter(
                redis, Clock.fixed(NOW.plusSeconds(60), java.time.ZoneOffset.UTC));
        assertThat(next.tryConsume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        String newWindowKey = keysSeen.get(keysSeen.size() - 1).get(0);
        assertThat(newWindowKey)
                .isNotEqualTo(exhaustedWindowKey)
                .contains("minute:1773577860:actor-1");
    }

    @Test
    void redisFailureFailsClosedWithAGenericSafeError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis-connection-refused-9z"));

        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(GatewayUsagePolicyCounterUnavailableException.class)
                .hasMessage(GatewayUsagePolicyCounterUnavailableException.MESSAGE)
                .hasMessage("Unable to check gateway usage policy limit.")
                .hasMessageNotContaining("redis-connection-refused-9z");
    }

    @Test
    void theUnavailableMessageLeaksNoRedisOrKeyDetail() {
        assertThat(GatewayUsagePolicyCounterUnavailableException.MESSAGE)
                .doesNotContain("localhost", "6379", "INCR", "actor-1", "aegivault", "PEXPIRE");
    }

    @Test
    void anEmptyScriptResultFailsClosedRatherThanRejecting() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(null);

        // "Could not tell" must not be reported as "limit spent": the caller
        // would see a false rejection and could retry forever, and a future
        // integration might map false to a user-visible error.
        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(GatewayUsagePolicyCounterUnavailableException.class)
                .hasMessage(GatewayUsagePolicyCounterUnavailableException.MESSAGE);
    }

    @Test
    void blankActorsAreRejectedWithoutTouchingRedis() {
        assertThatThrownBy(() -> consume(null, GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consume("  ", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(redis, times(0)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void invalidLimitsAreRejectedWithoutTouchingRedis() {
        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        verify(redis, times(0)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void aNullWindowAndNullCollaboratorsAreRejected() {
        assertThatThrownBy(() -> consume("actor-1", null, 5L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("window must not be null");
        assertThatThrownBy(() -> new RedisGatewayUsagePolicyCounter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("redis must not be null");
        assertThatThrownBy(() -> new RedisGatewayUsagePolicyCounter(redis, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void theCounterKnowsNothingAboutPoliciesOrTokens() {
        // This is the enforcement primitive, not policy interpretation: it
        // handles actor + window + limit and nothing else, and it has no
        // notion of a token window, a budget, or a resolved policy.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyCounter.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .toList())
                .containsExactly("tryConsume");
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyCounterWindow.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("MINUTE", "DAY");
    }
}
