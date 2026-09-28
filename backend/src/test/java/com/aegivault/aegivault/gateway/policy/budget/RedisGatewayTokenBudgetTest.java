package com.aegivault.aegivault.gateway.policy.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pure unit tests for {@link RedisGatewayTokenBudget} with a Mockito
 * {@link StringRedisTemplate} — no Spring context, no Redis server, no
 * network, matching the approach the existing Redis policy-counter test uses.
 *
 * <p>A recording answer stands in for the Lua execution and mirrors it
 * faithfully: it reads the day's held total, returns before any write when the
 * request does not fit, and otherwise increments and applies a TTL to a newly
 * created key. That makes "a rejection writes nothing" and "check and increment
 * are one indivisible step" properties that are observed, not assumed.
 *
 * <p>The script's own text is also asserted on, so the atomicity claim is
 * backed by the shipped Lua rather than only by the mock's behaviour.
 */
class RedisGatewayTokenBudgetTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:30:45.123Z");

    /** 2026-03-15T00:00:00Z, epoch second 1773532800. */
    private static final Instant DAY_START = Instant.parse("2026-03-15T00:00:00Z");

    private static final String ACTOR = "actor-1";

    private StringRedisTemplate redis;

    private final List<List<String>> keysSeen = Collections.synchronizedList(new ArrayList<>());

    private final List<Object[]> argsSeen = Collections.synchronizedList(new ArrayList<>());

    /** Stands in for the server-side held totals, one per Redis key. */
    private final Map<String, Long> serverHeld = new HashMap<>();

    /**
     * Stands in for the per-reservation hash fields, so a stored reservation can
     * be located by the id the caller was given — the property the in-memory
     * budget has and a bare total would not.
     */
    private final Map<String, Long> serverReservations = new HashMap<>();

    /** Stands in for Redis TTLs, one per key. */
    private final Map<String, Long> serverTtl = new HashMap<>();

    /**
     * Mirrors the script: read the {@code total} field, reject before any write
     * when the requested amount does not fit, otherwise record the total and
     * the reservation's own field, and set a TTL on a newly created key only.
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

            String key = keys.get(0);
            long requested = Long.parseLong((String) scriptArgs[0]);
            long limit = Long.parseLong((String) scriptArgs[1]);
            long ttl = Long.parseLong((String) scriptArgs[2]);
            String reservationId = (String) scriptArgs[3];
            long held = serverHeld.getOrDefault(key, 0L);

            if (held + requested > limit) {
                // The check precedes every write: no increment, no key creation,
                // no reservation field, no TTL change.
                return 0L;
            }
            serverHeld.put(key, held + requested);
            serverReservations.put(key + "#" + reservationId, requested);
            if (held == 0L) {
                serverTtl.put(key, ttl);
            }
            return 1L;
        };
    }

    /**
     * Mirrors the reconciliation script: find the reservation by id and return
     * before any write when it is absent, otherwise replace the held amount
     * with the actual one and drop the reservation. The TTL is deliberately not
     * touched, exactly as the shipped script does not touch it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Answer<Long> reconcileLuaScript() {
        return (InvocationOnMock invocation) -> {
            List<String> keys = List.copyOf((List<String>) invocation.getArgument(1));
            Object[] all = invocation.getArguments();
            Object[] scriptArgs = new Object[all.length - 2];
            System.arraycopy(all, 2, scriptArgs, 0, scriptArgs.length);
            keysSeen.add(keys);
            argsSeen.add(scriptArgs);

            String key = keys.get(0);
            String reservationId = (String) scriptArgs[0];
            long actual = Long.parseLong((String) scriptArgs[1]);
            Long reserved = serverReservations.get(key + "#" + reservationId);

            if (reserved == null) {
                // No write of any kind: the total, the reservation fields, and
                // the TTL are all left exactly as they were.
                return 0L;
            }
            long held = serverHeld.getOrDefault(key, 0L);
            serverHeld.put(key, held - reserved + actual);
            serverReservations.remove(key + "#" + reservationId);
            return 1L;
        };
    }

    /**
     * Answers either script, so a test can drive reservations and
     * reconciliations through one mock and still assert the interaction count of
     * each separately.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Answer<Long> eitherScript() {
        Answer<Long> reserve = luaScript();
        Answer<Long> reconcile = reconcileLuaScript();
        return (InvocationOnMock invocation) -> {
            RedisScript<?> script = (RedisScript<?>) invocation.getArgument(0);
            return RedisGatewayTokenBudget.RECONCILE_SCRIPT.equals(script)
                    ? reconcile.answer(invocation)
                    : reserve.answer(invocation);
        };
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stubRedis() {
        redis = mock(StringRedisTemplate.class);
        keysSeen.clear();
        argsSeen.clear();
        serverHeld.clear();
        serverReservations.clear();
        serverTtl.clear();
        // Both arities are stubbed: production passes four script arguments to a
        // reservation and two to a reconciliation, plus the key list.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenAnswer(eitherScript());
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenAnswer(eitherScript());
    }

    private RedisGatewayTokenBudget budget() {
        return new RedisGatewayTokenBudget(redis, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GatewayTokenBudgetReservation reserve(String actor, long limit, long tokens) {
        return budget().tryReserve(actor, DAY_START, limit, tokens);
    }

    /** The server-side held total for the default actor and day. */
    private long heldFor() {
        return serverHeld.getOrDefault(
                GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR), 0L);
    }

    private GatewayTokenBudgetReconciliation settle(String reservationId, long actual) {
        return budget().reconcile(ACTOR, DAY_START, reservationId, actual);
    }

    /** Reserves and returns the id a caller would hold for later settlement. */
    private String reserveId(long limit, long tokens) {
        return reserve(ACTOR, limit, tokens).reservationId();
    }

    /** The server-side total for an arbitrary actor and day. */
    private long heldFor(String actor, Instant dayStart) {
        return serverHeld.getOrDefault(GatewayTokenBudgetWindow.DAY.keyFor(dayStart, actor), 0L);
    }

    @Test
    void aReservationWithinTheLimitSucceeds() {
        GatewayTokenBudgetReservation result = reserve(ACTOR, 1_000L, 400L);

        assertThat(result.isReserved()).isTrue();
        assertThat(result.reservedTokens()).isEqualTo(400L);
        assertThat(result.reservationId()).isNotBlank();
        assertThat(heldFor()).isEqualTo(400L);
    }

    @Test
    void aReservationIsExactlyOneAtomicScriptExecution() {
        // The point of the abstraction: one round trip reads, decides, and
        // reserves, never a read followed by a separate increment.
        reserve(ACTOR, 1_000L, 400L);

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
        verifyNoMoreInteractions(redis);
    }

    @Test
    void theCheckAndTheWritesAreInTheSameScript() {
        reserve(ACTOR, 1_000L, 400L);

        // Asserted on the shipped Lua, not just on the mock's behaviour: the
        // comparison must precede every write, the writes must be the server's
        // own, and the TTL must be applied to a newly created key only.
        String script = scriptText();
        assertThat(script).contains("redis.call('HGET', KEYS[1], 'total')");
        int check = script.indexOf("> limit then");
        assertThat(check).as("the comparison exists").isPositive();
        assertThat(script.indexOf("redis.call('HSET'"))
                .as("every write comes after the check")
                .isGreaterThan(check);
        assertThat(script.indexOf("PEXPIRE"))
                .as("the TTL is written last")
                .isGreaterThan(script.lastIndexOf("redis.call('HSET'"));
        assertThat(script).contains("if held == 0 then")
                .as("a TTL is applied only to a newly created key")
                .doesNotContain("DEL");
    }

    @Test
    void aSuccessfulReservationIsStoredUnderTheIdItReports() {
        GatewayTokenBudgetReservation result = reserve(ACTOR, 1_000L, 400L);

        // The minimum reconciliation contract: the id the caller was handed is
        // the one the amount is actually held under, so a later step can settle
        // exactly this reservation without re-supplying the amount.
        String key = GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR);
        assertThat(serverReservations).containsEntry(key + "#" + result.reservationId(), 400L);
        assertThat(heldFor()).isEqualTo(400L);
    }

    @Test
    void eachReservationIsStoredSeparatelyUnderItsOwnId() {
        GatewayTokenBudgetReservation first = reserve(ACTOR, 1_000L, 400L);
        GatewayTokenBudgetReservation second = budget().tryReserve(ACTOR, DAY_START, 1_000L, 600L);

        String key = GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR);
        assertThat(serverReservations)
                .containsEntry(key + "#" + first.reservationId(), 400L)
                .containsEntry(key + "#" + second.reservationId(), 600L);
        assertThat(heldFor()).as("the total is the sum of the reservations").isEqualTo(1_000L);
    }

    @Test
    void theBudgetKeyUsesTheDocumentedTokenBudgetNamespace() {
        reserve(ACTOR, 1_000L, 400L);

        // Deterministic documented key shape:
        // aegivault:gateway:token-budget:<windowStart>:<actorSubject>
        assertThat(keysSeen).containsExactly(List.of(
                "aegivault:gateway:token-budget:1773532800:actor-1"));
    }

    @Test
    void budgetKeysCanNeverCollideWithTheRequestCounterOrRateLimiterKeys() {
        reserve(ACTOR, 1_000L, 400L);

        for (String key : keysSeen.get(0)) {
            assertThat(key).startsWith(GatewayTokenBudgetWindow.KEY_PREFIX)
                    .doesNotContain("policy-counter", "rate-limit");
        }
        assertThat(GatewayTokenBudgetWindow.KEY_PREFIX)
                .isNotEqualTo(com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterWindow.KEY_PREFIX)
                .isNotEqualTo(com.aegivault.aegivault.gateway.GatewayRateLimitPolicy.KEY_PREFIX);
    }

    @Test
    void separateActorsUseDifferentKeys() {
        reserve("actor-1", 1_000L, 400L);
        reserve("actor-2", 1_000L, 400L);

        assertThat(keysSeen).containsExactly(
                List.of("aegivault:gateway:token-budget:1773532800:actor-1"),
                List.of("aegivault:gateway:token-budget:1773532800:actor-2"));
        // One actor's spending cannot move the other's total.
        assertThat(serverHeld).hasSize(2);
    }

    @Test
    void separateDaysUseDifferentKeys() {
        reserve(ACTOR, 1_000L, 400L);
        budget().tryReserve(ACTOR, Instant.parse("2026-03-16T00:00:00Z"), 1_000L, 400L);

        // A new day is a new key, not a reset of the old one.
        assertThat(keysSeen).containsExactly(
                List.of("aegivault:gateway:token-budget:1773532800:actor-1"),
                List.of("aegivault:gateway:token-budget:1773619200:actor-1"));
    }

    @Test
    void aReservationExactlyFillingTheRemainingCapacitySucceeds() {
        reserve(ACTOR, 1_000L, 600L);

        assertThat(reserve(ACTOR, 1_000L, 400L).isReserved())
                .as("the boundary is inclusive: held + requested == limit fits")
                .isTrue();
        assertThat(heldFor()).isEqualTo(1_000L);
    }

    @Test
    void aReservationExceedingTheLimitIsRejected() {
        reserve(ACTOR, 1_000L, 600L);

        assertThat(reserve(ACTOR, 1_000L, 401L).isReserved()).isFalse();
    }

    @Test
    void aRejectedReservationWritesNothingAtAll() {
        reserve(ACTOR, 1_000L, 1_000L);
        int reservationsBefore = serverReservations.size();

        // A refused attempt must not move the total or record a reservation; a
        // stored rejection would hand the actor free capacity.
        GatewayTokenBudgetReservation rejected = reserve(ACTOR, 1_000L, 1L);

        assertThat(rejected.isReserved()).isFalse();
        assertThat(heldFor()).isEqualTo(1_000L);
        assertThat(serverReservations).hasSize(reservationsBefore);
        assertThat(serverReservations.values()).doesNotContain(1L);
    }

    @Test
    void rejectedReservationsDoNotConsumeFurtherCapacity() {
        reserve(ACTOR, 1_000L, 1_000L);

        for (int i = 0; i < 5; i++) {
            assertThat(reserve(ACTOR, 1_000L, 1L).isReserved()).isFalse();
        }

        // Still exactly at the limit: hammering a spent budget neither inflates
        // the held total nor pushes it past the limit.
        assertThat(heldFor()).isEqualTo(1_000L);
    }

    @Test
    void aRejectionOnAKeyThatDoesNotExistYetCreatesNothing() {
        // Nothing was ever reserved, so the key was never created and the
        // rejection must leave no trace of a key or a TTL behind.
        assertThat(reserve(ACTOR, 10L, 11L).isReserved()).isFalse();

        assertThat(heldFor()).isZero();
        assertThat(serverHeld).isEmpty();
        assertThat(serverTtl).as("no key, so no TTL was ever applied").isEmpty();
    }

    @Test
    void theTtlCoversTheRestOfTheDayPlusTheClockSkewGrace() {
        reserve(ACTOR, 1_000L, 400L);

        // NOW is 12:30:45.123 into a day ending at the next midnight, so the TTL
        // is what is left of that day plus the one-second grace.
        long expected = Duration.ofHours(11).plusMinutes(29).plusSeconds(14)
                .plusMillis(877).toMillis() + 1_000L;
        assertThat(serverTtl.get(GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR)))
                .isEqualTo(expected);
    }

    @Test
    void aTtlIsAppliedOnlyWhenTheKeyIsFirstCreated() {
        reserve(ACTOR, 1_000L, 400L);
        Long ttlAfterFirst = serverTtl.get(GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR));

        reserve(ACTOR, 1_000L, 400L);

        // Re-applying the TTL on every reservation would silently keep pushing
        // the expiry out and so retain a previous day's budget.
        assertThat(serverTtl.get(GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR)))
                .isEqualTo(ttlAfterFirst);
    }

    @Test
    void aTtlIsAlwaysAtLeastTheGracePeriod() {
        // A reservation made at the very last instant of a day still gets a
        // positive TTL (one millisecond of window plus the one-second grace), so
        // a key is never born already expired.
        long ttl = GatewayTokenBudgetWindow.DAY.ttlMillis(
                DAY_START, DAY_START.plus(Duration.ofDays(1)));

        assertThat(ttl).isEqualTo(1L + 1_000L);
        assertThat(GatewayTokenBudgetWindow.DAY.ttlMillis(DAY_START, NOW))
                .as("a reservation inside the day covers the rest of it")
                .isGreaterThan(Duration.ofHours(11).toMillis());
    }

    @Test
    void redisFailureFailsClosedWithAGenericSafeError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis-connection-refused-9z"));

        assertThatThrownBy(() -> reserve(ACTOR, 1_000L, 400L))
                .isInstanceOf(GatewayTokenBudgetUnavailableException.class)
                .hasMessage(GatewayTokenBudgetUnavailableException.MESSAGE)
                .hasMessageNotContaining("redis-connection-refused-9z");
    }

    @Test
    void aRedisFailureIsNeitherReservedNorRejected() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("down"));

        // "Could not tell" must never be reported as either outcome: a rejection
        // would claim the budget is spent, and a reservation would spend tokens
        // against a budget that could not be checked.
        assertThatThrownBy(() -> reserve(ACTOR, 1_000L, 400L))
                .isInstanceOf(GatewayTokenBudgetUnavailableException.class);
    }
    @Test
    void anEmptyScriptResultFailsClosedRatherThanReserving() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> reserve(ACTOR, 1_000L, 400L))
                .isInstanceOf(GatewayTokenBudgetUnavailableException.class)
                .hasMessage(GatewayTokenBudgetUnavailableException.MESSAGE);
    }

    @Test
    void anUnrecognisedScriptResultFailsClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(7L);

        // Only 1 and 0 mean anything; anything else is a broken or unexpected
        // script and must never be read as a reservation.
        assertThatThrownBy(() -> reserve(ACTOR, 1_000L, 400L))
                .isInstanceOf(GatewayTokenBudgetUnavailableException.class);
    }

    @Test
    void theSafeExceptionLeaksNoRedisHostKeyOrActor() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connect to redis://10.0.0.7:6379 for key "
                        + GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR)
                        + " failed for " + ACTOR));

        assertThatThrownBy(() -> reserve(ACTOR, 1_000L, 400L))
                .isInstanceOf(GatewayTokenBudgetUnavailableException.class)
                .hasMessage(GatewayTokenBudgetUnavailableException.MESSAGE);
        for (String secret : List.of("10.0.0.7", "6379", "token-budget", ACTOR)) {
            assertThatThrownBy(() -> reserve(ACTOR, 1_000L, 400L))
                    .as("the safe message must not leak %s", secret)
                    .hasMessageNotContaining(secret);
        }
    }

    @Test
    void invalidInputIsRejectedBeforeRedisIsContacted() {
        assertThatThrownBy(() -> budget().tryReserve("  ", DAY_START, 1_000L, 400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> budget().tryReserve(null, DAY_START, 1_000L, 400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> budget().tryReserve(ACTOR, null, 1_000L, 400L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("windowStart must not be null");
        assertThatThrownBy(() -> budget().tryReserve(ACTOR, DAY_START, 0L, 400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> budget().tryReserve(ACTOR, DAY_START, 1_000L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestedTokens must be positive");

        // Not one round trip was spent on any of them.
        verifyNoInteractions(redis);
    }

    @Test
    void aPartialDayStartIsRejected() {
        // A mid-day start names no real UTC day: it would mint a key no other
        // caller derives and silently split one day's budget into two.
        assertThatThrownBy(() -> budget().tryReserve(ACTOR, NOW, 1_000L, 400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("windowStart must be a UTC day start");

        verifyNoInteractions(redis);
    }

    @Test
    void theActorIsTrimmedLikeTheRestOfThePolicyPackage() {
        budget().tryReserve("  " + ACTOR + "  ", DAY_START, 1_000L, 400L);

        // The trimmed subject is the one keyed, so an untrimmed spelling of the
        // same actor cannot start a second budget for that actor.
        assertThat(keysSeen).containsExactly(List.of(
                "aegivault:gateway:token-budget:1773532800:actor-1"));
    }

    @Test
    void reservationIdsAreUniqueAcrossAttempts() {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(reserve(ACTOR, 1_000L, 1L).reservationId());
        }

        assertThat(ids).hasSize(20);
    }

    @Test
    void aNullRedisTemplateIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new RedisGatewayTokenBudget(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("redis must not be null");
        assertThatThrownBy(() -> new RedisGatewayTokenBudget(redis, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void aReconciliationIsExactlyOneAtomicScriptExecution() {
        String id = reserveId(1_000L, 400L);
        // The setup reservation is a separate call, so it is cleared first: the
        // assertion is about the reconciliation alone.
        clearInvocations(redis);

        settle(id, 80L);

        // The lookup, the removal, and the adjustment are one indivisible
        // server-side step: never a read, a Java-side compare, then a delete and
        // an increment as separate round trips.
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any());
        verifyNoMoreInteractions(redis);
    }

    @Test
    void theLookupAndTheAdjustmentAreInTheSameScript() {
        settle(reserveId(1_000L, 400L), 80L);

        // Asserted on the shipped Lua, so the atomicity claim is backed by the
        // script itself and not only by the mock's behaviour.
        String script = reconcileScriptText();
        assertThat(script).contains("redis.call('HGET', KEYS[1], field)");
        int missing = script.indexOf("if not reserved then");
        assertThat(missing).as("the absence check exists").isPositive();
        assertThat(script.indexOf("redis.call('HSET'"))
                .as("every write comes after the absence check")
                .isGreaterThan(missing);
        assertThat(script.indexOf("redis.call('HDEL'"))
                .as("the reservation is removed in the same script")
                .isGreaterThan(missing);
        assertThat(script)
                .as("no TTL is re-applied, so the day's expiry is untouched")
                .doesNotContain("PEXPIRE", "EXPIRE");
    }

    @Test
    void theReservedAmountIsReplacedByTheActualUsage() {
        settle(reserveId(1_000L, 400L), 80L);

        // The script subtracts what was held and adds what was really spent, so
        // 320 units of over-reservation are released back to the day.
        assertThat(heldFor()).isEqualTo(80L);
    }

    @Test
    void reconciliationRemovesTheReservationField() {
        String id = reserveId(1_000L, 400L);
        String key = GatewayTokenBudgetWindow.DAY.keyFor(DAY_START, ACTOR);

        settle(id, 400L);

        // Reservations are single-use: the field is gone, so a replay can never
        // settle the same tokens a second time.
        assertThat(serverReservations).doesNotContainKey(key + "#" + id);
    }

    @Test
    void anExactReconciliationLeavesTheTotalUnchanged() {
        settle(reserveId(1_000L, 400L), 400L);

        assertThat(heldFor()).isEqualTo(400L);
    }

    @Test
    void aPartialReleaseIsAccountedExactly() {
        settle(reserveId(1_000L, 400L), 20L);

        assertThat(heldFor()).isEqualTo(20L);
    }

    @Test
    void anOverReservationIsRecordedAsTheFullActualUsage() {
        settle(reserveId(1_000L, 400L), 540L);

        // Never clamped: the provider really spent 540 tokens, so 540 is what
        // the day now carries, even though only 400 was held for it.
        assertThat(heldFor()).isEqualTo(540L);
    }

    @Test
    void zeroActualTokensIsAcceptedAndReleasesTheWholeReservation() {
        settle(reserveId(1_000L, 400L), 0L);

        // A provider can legitimately report no tokens; the day then carries
        // nothing for that request.
        assertThat(heldFor()).isZero();
    }

    /** The Lua text the production class actually ships for a reconciliation. */
    private String reconcileScriptText() {
        return RedisGatewayTokenBudget.RECONCILE_SCRIPT.getScriptAsString();
    }

    /** The Lua text the production class actually ships. */
    private String scriptText() {
        return RedisGatewayTokenBudget.TRY_RESERVE_SCRIPT.getScriptAsString();
    }
}