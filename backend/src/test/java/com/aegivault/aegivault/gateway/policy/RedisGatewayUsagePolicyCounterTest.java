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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * already uses. A recording answer stands in for the Lua execution and
 * simulates its two phases faithfully: it checks every requested key first
 * and returns before writing anything if one is exhausted, so "a rejection
 * increments nothing" is proven rather than assumed.
 */
class RedisGatewayUsagePolicyCounterTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    private StringRedisTemplate redis;

    private final List<List<String>> keysSeen = Collections.synchronizedList(new ArrayList<>());

    private final List<Object[]> argsSeen = Collections.synchronizedList(new ArrayList<>());

    /** Stands in for the server-side counters, one per Redis key. */
    private final Map<String, Long> serverCounters = new HashMap<>();

    /**
     * Mirrors the two-phase script exactly: check every key, return on the
     * first exhausted one without writing, otherwise increment them all.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Answer<Long> luaScript() {
        return (InvocationOnMock invocation) -> {
            List<String> keys = List.copyOf((List<String>) invocation.getArgument(1));
            Object[] all = invocation.getArguments();
            Object[] scriptArgs = new Object[all.length - 2];
            System.arraycopy(all, 2, scriptArgs, 0, scriptArgs.length);
            keysSeen.add(keys);
            argsSeen.add(scriptArgs);

            // Phase one: read-only checks.
            for (int i = 0; i < keys.size(); i++) {
                long limit = Long.parseLong((String) scriptArgs[i * 2]);
                long current = serverCounters.getOrDefault(keys.get(i), 0L);
                if (current + 1L > limit) {
                    return -(long) (i + 1);
                }
            }
            // Phase two: every window had room, so increment them all.
            for (String key : keys) {
                serverCounters.merge(key, 1L, Long::sum);
            }
            return (long) keys.size();
        };
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stubRedis() {
        redis = mock(StringRedisTemplate.class);
        keysSeen.clear();
        argsSeen.clear();
        serverCounters.clear();
        // Production passes one (limit, ttl) pair per requested window, so the
        // two arities are a single-window and a two-window request.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(luaScript());
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenAnswer(luaScript());
    }

    private RedisGatewayUsagePolicyCounter counter() {
        return new RedisGatewayUsagePolicyCounter(redis, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private boolean consume(String actor, GatewayUsagePolicyCounterWindow window, long limit) {
        return counter().tryConsume(actor, window, limit);
    }

    private GatewayUsagePolicyCounterResult both(long perDay, long perMinute) {
        return counter().tryConsume(GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, perDay, perMinute));
    }

    /** The server-side count for this actor in one window at {@link #NOW}. */
    private long countFor(GatewayUsagePolicyCounterWindow window) {
        return serverCounters.getOrDefault(
                GatewayUsagePolicyCounterRequest.of("actor-1", NOW, window, 1L).keyFor(window), 0L);
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
    void aMultiWindowAttemptIsExactlyOneAtomicScriptExecution() {
        // The whole point: one round trip decides and consumes both windows,
        // never one script per window and never a read-then-write split.
        both(100L, 2L);

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
        verifyNoMoreInteractions(redis);
    }

    @Test
    void bothWindowKeysAreSentInOneCallInDayThenMinuteOrder() {
        both(100L, 2L);

        // Deterministic documented key shape, unchanged by this upgrade:
        // aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>
        assertThat(keysSeen).containsExactly(List.of(
                "aegivault:gateway:policy-counter:day:1773532800:actor-1",
                "aegivault:gateway:policy-counter:minute:1773577800:actor-1"));
    }

    @Test
    void eachWindowContributesItsLimitAndTtlAsScriptArguments() {
        both(100L, 2L);

        Object[] scriptArgs = argsSeen.get(0);
        assertThat(scriptArgs).hasSize(4);
        assertThat(scriptArgs[0]).isEqualTo("100");
        assertThat(scriptArgs[2]).isEqualTo("2");
        // Both TTLs are positive and cover the rest of their own window.
        assertThat(Long.parseLong((String) scriptArgs[1])).isPositive();
        assertThat(Long.parseLong((String) scriptArgs[3])).isPositive();
    }

    @Test
    void anAllowedMultiWindowAttemptIncrementsBothCounters() {
        assertThat(both(100L, 2L).isAllowed()).isTrue();

        assertThat(countFor(GatewayUsagePolicyCounterWindow.DAY)).as("day count").isEqualTo(1L);
        assertThat(countFor(GatewayUsagePolicyCounterWindow.MINUTE)).as("minute count").isEqualTo(1L);
    }

    @Test
    void aMinuteRejectionIncrementsNeitherCounter() {
        // Exhaust the minute window, leaving day capacity to spare.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        var result = both(100L, 1L);

        // Rejected on minute, and the day counter is untouched: no partial
        // consumption.
        assertThat(result.state()).isEqualTo(GatewayUsagePolicyCounterResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
        assertThat(countFor(GatewayUsagePolicyCounterWindow.DAY))
                .as("day count after rejection")
                .isZero();
        assertThat(countFor(GatewayUsagePolicyCounterWindow.MINUTE))
                .as("minute count after rejection")
                .isEqualTo(1L);
    }

    @Test
    void aDayRejectionIncrementsNeitherCounter() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();

        var result = both(1L, 100L);

        assertThat(result.state()).isEqualTo(GatewayUsagePolicyCounterResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
        assertThat(countFor(GatewayUsagePolicyCounterWindow.DAY))
                .as("day count after rejection")
                .isEqualTo(1L);
        assertThat(countFor(GatewayUsagePolicyCounterWindow.MINUTE))
                .as("minute count after rejection")
                .isZero();
    }

    @Test
    void bothExhaustedReportsTheDayWindowDeterministically() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();

        var result = both(1L, 1L);

        // DAY is reported because the script checks keys in the caller's fixed
        // order and returns the first exhausted position.
        assertThat(result.state()).isEqualTo(GatewayUsagePolicyCounterResult.State.REJECTED);
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.DAY);
    }

    @Test
    void aRejectionAtTheLastPositionIsNotMistakenForAnAdmission() {
        // The script returns -i for a rejection and +n for an admission. With
        // two windows a naive encoding would return 2 for both "second window
        // rejected" and "admitted", so the sign is what keeps them apart.
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 1L)).isTrue();
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 100L)).isTrue();

        // Day 2 <= 100 so the first key passes; minute is full, so the second
        // key rejects at position 2 and must not read as an admission.
        var result = both(100L, 1L);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.rejectedWindow()).isEqualTo(GatewayUsagePolicyCounterWindow.MINUTE);
    }

    @Test
    void aSingleWindowAttemptStillUsesOneScriptExecution() {
        assertThat(consume("actor-1", GatewayUsagePolicyCounterWindow.DAY, 5L)).isTrue();

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void theScriptChecksBeforeItWritesAndAppliesTtlsToNewKeys() {
        String script = RedisGatewayUsagePolicyCounter.TRY_CONSUME_ALL_SCRIPT.getScriptAsString();

        // The read-only check loop runs before any write, and increments only
        // appear in the second loop, so a rejection cannot have written.
        assertThat(script).contains("GET").contains("INCR").contains("PEXPIRE");
        assertThat(script.indexOf("current + 1 > limit"))
                .as("the rejection check exists")
                .isPositive();
        assertThat(script.indexOf("INCR"))
                .as("increments come after the check")
                .isGreaterThan(script.indexOf("current + 1 > limit"));
        assertThat(script).contains("if current == 0 then")
                .as("TTL is applied only to newly created keys");
    }

    @Test
    void policyKeysCanNeverCollideWithTheGlobalRateLimiterKeys() {
        both(100L, 2L);

        for (String key : keysSeen.get(0)) {
            assertThat(key).startsWith(GatewayUsagePolicyCounterWindow.KEY_PREFIX).doesNotContain("rate-limit");
        }
    }

    @Test
    void separateActorsUseDifferentKeys() {
        counter().tryConsume(GatewayUsagePolicyCounterRequest.ofBoth("actor-1", NOW, 5L, 5L));
        counter().tryConsume(GatewayUsagePolicyCounterRequest.ofBoth("actor-2", NOW, 5L, 5L));

        assertThat(keysSeen).containsExactly(
                List.of(
                        "aegivault:gateway:policy-counter:day:1773532800:actor-1",
                        "aegivault:gateway:policy-counter:minute:1773577800:actor-1"),
                List.of(
                        "aegivault:gateway:policy-counter:day:1773532800:actor-2",
                        "aegivault:gateway:policy-counter:minute:1773577800:actor-2"));
    }

    @Test
    void theMinuteTtlCoversTheRestOfTheWindowPlusGrace() {
        consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 5L);

        // NOW is 12:30:45.123 into a minute ending at 12:31:00: 14.877s left,
        // plus the one-second clock-skew grace.
        long ttl = Long.parseLong((String) argsSeen.get(0)[1]);
        assertThat(ttl).isEqualTo(15_000L - 123L + GatewayUsagePolicyCounterWindow.EXPIRY_GRACE.toMillis());
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
    void redisFailureFailsClosedWithAGenericSafeError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis-connection-refused-9z"));

        assertThatThrownBy(() -> both(100L, 2L))
                .isInstanceOf(GatewayUsagePolicyCounterUnavailableException.class)
                .hasMessage(GatewayUsagePolicyCounterUnavailableException.MESSAGE)
                .hasMessageNotContaining("redis-connection-refused-9z");
    }

    @Test
    void aFailureDuringAMultiWindowAttemptIsNeitherAllowedNorRejected() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("down"));

        // "Could not check" must never be reported as either outcome: a
        // rejection would wrongly say the limit was exceeded, and an
        // admission would let a request through on a broken counter.
        assertThatThrownBy(() -> both(100L, 2L))
                .isInstanceOf(GatewayUsagePolicyCounterUnavailableException.class);
    }

    @Test
    void anEmptyScriptResultFailsClosedRatherThanAdmitting() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> both(100L, 2L))
                .isInstanceOf(GatewayUsagePolicyCounterUnavailableException.class)
                .hasMessage(GatewayUsagePolicyCounterUnavailableException.MESSAGE);
    }

    @Test
    void anUnrecognisedScriptResultFailsClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(99L);

        assertThatThrownBy(() -> both(100L, 2L))
                .isInstanceOf(GatewayUsagePolicyCounterUnavailableException.class);
    }

    @Test
    void theUnavailableMessageLeaksNoRedisOrKeyDetail() {
        assertThat(GatewayUsagePolicyCounterUnavailableException.MESSAGE)
                .doesNotContain("localhost", "6379", "INCR", "actor-1", "aegivault", "PEXPIRE");
    }

    @Test
    void blankActorsAndInvalidLimitsAreRejectedWithoutTouchingRedis() {
        assertThatThrownBy(() -> consume(null, GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> consume("  ", GatewayUsagePolicyCounterWindow.MINUTE, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> consume("actor-1", GatewayUsagePolicyCounterWindow.MINUTE, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> consume("actor-1", null, 5L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("window must not be null");

        verify(redis, times(0)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void aNullCollaboratorIsRejected() {
        assertThatThrownBy(() -> new RedisGatewayUsagePolicyCounter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("redis must not be null");
        assertThatThrownBy(() -> new RedisGatewayUsagePolicyCounter(redis, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void theCounterKnowsNothingAboutPoliciesOrTokens() {
        // The enforcement primitive, not policy interpretation: it handles
        // actor + window + limit and has no notion of a token window, a
        // budget, or a resolved policy.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyCounterWindow.values())
                        .map(Enum::name)
                        .toList())
                .containsExactly("MINUTE", "DAY");
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyCounterRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("actorSubject", "now", "limits");
    }
}
