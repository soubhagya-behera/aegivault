package com.aegivault.aegivault.gateway.policy.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link InMemoryGatewayTokenBudget} — no Spring context,
 * no Redis, no database, no network.
 *
 * <p>Each test states the property that makes the primitive safe (an
 * all-or-nothing atomic decision, a rejection that costs nothing, isolation
 * between actors and days) and then asserts the consequence, rather than
 * asserting an implementation detail, so a later reconciliation step can change
 * the internals without rewriting these tests.
 */
class InMemoryGatewayTokenBudgetTest {

    /** 2026-03-15T00:00:00Z — a fixed UTC day start. */
    private static final Instant DAY_START = Instant.parse("2026-03-15T00:00:00Z");

    /** The following UTC day. */
    private static final Instant NEXT_DAY_START = Instant.parse("2026-03-16T00:00:00Z");

    private static final String ACTOR = "actor-1";

    private InMemoryGatewayTokenBudget budget;

    @BeforeEach
    void setUp() {
        budget = new InMemoryGatewayTokenBudget(
                Clock.fixed(Instant.parse("2026-03-15T12:30:45.123Z"), ZoneOffset.UTC));
    }

    private GatewayTokenBudgetReservation reserve(String actor, long limit, long tokens) {
        return budget.tryReserve(actor, DAY_START, limit, tokens);
    }

    /** Reserves for the default day and returns the id a caller would hold. */
    private String reserveId(long limit, long tokens) {
        return reserve(ACTOR, limit, tokens).reservationId();
    }

    /** Settles a reservation on the default actor and day. */
    private GatewayTokenBudgetReconciliation settle(String reservationId, long actual) {
        return budget.reconcile(ACTOR, DAY_START, reservationId, actual);
    }

    @Test
    void aReservationWithinTheLimitSucceeds() {
        GatewayTokenBudgetReservation result = reserve(ACTOR, 1_000L, 400L);

        assertThat(result.state()).isEqualTo(GatewayTokenBudgetReservation.State.RESERVED);
        assertThat(result.isReserved()).isTrue();
        // The caller gets back exactly what it asked to hold, so it can settle
        // the reservation later without re-supplying the amount.
        assertThat(result.reservedTokens()).isEqualTo(400L);
        assertThat(result.reservationId()).isNotBlank();
    }

    @Test
    void aReservationExactlyFillingTheRemainingCapacitySucceeds() {
        // The boundary is <=, not <: a limit is the highest permitted total, so
        // reserving exactly what is left must be admitted, and the attempt
        // after it must be refused.
        assertThat(reserve(ACTOR, 1_000L, 600L).isReserved()).isTrue();

        assertThat(reserve(ACTOR, 1_000L, 400L).isReserved())
                .as("the last 400 tokens exactly fill the remaining 400")
                .isTrue();

        assertThat(reserve(ACTOR, 1_000L, 1L).isReserved())
                .as("the day is now fully reserved")
                .isFalse();
    }

    @Test
    void aReservationExceedingTheCapacityIsRejected() {
        assertThat(reserve(ACTOR, 1_000L, 1_000L).isReserved()).isTrue();

        GatewayTokenBudgetReservation result = reserve(ACTOR, 1_000L, 1L);

        assertThat(result.state()).isEqualTo(GatewayTokenBudgetReservation.State.REJECTED);
        assertThat(result.isReserved()).isFalse();
    }

    @Test
    void aRejectedReservationChangesNothing() {
        reserve(ACTOR, 100L, 60L);

        // Hammer a nearly-spent budget: a refusal must not reserve anything,
        // not even partially, or repeated attempts would walk the day's total
        // further and further past the limit.
        for (int i = 0; i < 5; i++) {
            assertThat(reserve(ACTOR, 100L, 50L).isReserved()).isFalse();
        }

        // Proof that nothing was consumed: the 40 tokens still unused are
        // exactly still reservable, and not one token less.
        assertThat(reserve(ACTOR, 100L, 40L).isReserved())
                .as("a rejected attempt reserved nothing")
                .isTrue();
        assertThat(reserve(ACTOR, 100L, 1L).isReserved()).isFalse();
    }

    @Test
    void aRejectionCarriesNoReservationId() {
        reserve(ACTOR, 1_000L, 1_000L);

        GatewayTokenBudgetReservation rejected = reserve(ACTOR, 1_000L, 1L);

        // A refusal holds nothing, so an id would imply capacity was set aside.
        assertThat(rejected.reservationId()).isNull();
        assertThat(rejected.reservedTokens()).isZero();
    }

    @Test
    void theResultModelExposesNoCountsOrRemainingBudget() {
        // A rejection must not tell the caller how much is left, so the result
        // type itself must not be able to: its whole surface is the state, the
        // id, and the amount this one reservation holds.
        assertThat(List.of(GatewayTokenBudgetReservation.class.getRecordComponents()))
                .extracting(component -> component.getName())
                .containsExactlyInAnyOrder("state", "reservationId", "reservedTokens");
    }

    @Test
    void separateActorsHaveIndependentBudgets() {
        assertThat(reserve("actor-1", 100L, 100L).isReserved()).isTrue();

        // Actor 2 is untouched by actor 1 spending its own budget: sharing one
        // budget would let one actor exhaust another's allowance.
        assertThat(reserve("actor-2", 100L, 100L).isReserved()).isTrue();
        assertThat(reserve("actor-1", 100L, 1L).isReserved()).isFalse();
        assertThat(reserve("actor-2", 100L, 1L).isReserved()).isFalse();
    }

    @Test
    void separateDaysHaveIndependentBudgets() {
        assertThat(reserve(ACTOR, 100L, 100L).isReserved()).isTrue();
        assertThat(reserve(ACTOR, 100L, 1L).isReserved()).isFalse();

        // The next UTC day is a fresh budget, not the spent one: a day's
        // reservations must never bleed into the following day.
        assertThat(budget.tryReserve(ACTOR, NEXT_DAY_START, 100L, 100L).isReserved()).isTrue();
        assertThat(budget.tryReserve(ACTOR, NEXT_DAY_START, 100L, 1L).isReserved()).isFalse();
    }

    @Test
    void reservationsAccumulateAcrossManyAttempts() {
        // Twenty separate attempts of 10 tokens under a limit of 200 must all be
        // admitted, and the twenty-first must not: the boundary is on the
        // accumulated total, not on any single attempt.
        for (int i = 0; i < 20; i++) {
            assertThat(reserve(ACTOR, 200L, 10L).isReserved())
                    .as("attempt %d of 20", i + 1)
                    .isTrue();
        }
        assertThat(reserve(ACTOR, 200L, 10L).isReserved()).isFalse();
    }

    @Test
    void everyReservationGetsItsOwnUniqueId() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            GatewayTokenBudgetReservation result = reserve(ACTOR, 1_000L, 1L);
            assertThat(result.reservationId()).isNotBlank();
            // A duplicate id would make two different reservations settle as one.
            assertThat(ids.add(result.reservationId()))
                    .as("reservation %d reused an id", i)
                    .isTrue();
        }
        assertThat(ids).hasSize(25);
    }

    @Test
    void theActorIsTrimmedLikeTheRestOfThePolicyPackage() {
        assertThat(budget.tryReserve("  actor-1  ", DAY_START, 100L, 100L).isReserved()).isTrue();

        // The trimmed subject is the one accounted, so an untrimmed spelling of
        // the same actor cannot start a second budget for that actor.
        assertThat(reserve(ACTOR, 100L, 1L).isReserved()).isFalse();
    }

    @Test
    void aBlankActorIsRejectedBeforeAnyStateIsCreated() {
        assertThatThrownBy(() -> reserve(null, 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> reserve("   ", 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");

        // A rejected argument must leave nothing behind: a later valid attempt
        // still sees the whole budget available.
        assertThat(reserve(ACTOR, 100L, 100L).isReserved()).isTrue();
    }

    @Test
    void aNullWindowStartIsRejected() {
        assertThatThrownBy(() -> budget.tryReserve(ACTOR, null, 100L, 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("windowStart must not be null");
    }

    @Test
    void aWindowStartThatIsNotAUtcDayStartIsRejected() {
        // A partial-day start names no real UTC day: it would mint a window no
        // other caller derives and silently split one day's budget in two.
        assertThatThrownBy(() -> budget.tryReserve(
                ACTOR, Instant.parse("2026-03-15T12:00:00Z"), 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("windowStart must be a UTC day start");
        assertThatThrownBy(() -> budget.tryReserve(
                ACTOR, Instant.parse("2026-03-15T00:00:01Z"), 100L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("windowStart must be a UTC day start");
    }

    @Test
    void aNonPositiveLimitIsRejected() {
        // A zero or negative limit is a contradiction, not a limit: nothing
        // could ever satisfy the capacity check, so accepting it would quietly
        // mean "deny every token" instead of expressing an intent.
        assertThatThrownBy(() -> reserve(ACTOR, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
        assertThatThrownBy(() -> reserve(ACTOR, -1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");
    }

    @Test
    void aNonPositiveRequestedAmountIsRejected() {
        // Reserving zero would report a successful reservation while holding
        // nothing, and a negative amount would hand capacity back on every call.
        assertThatThrownBy(() -> reserve(ACTOR, 100L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestedTokens must be positive");
        assertThatThrownBy(() -> reserve(ACTOR, 100L, -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestedTokens must be positive");
    }

    @Test
    void aReservationLargerThanTheWholeLimitIsRejected() {
        // Nothing partial is ever reserved: an attempt for more than the entire
        // day cannot be satisfied at all, so it must not reserve a slice of it.
        assertThat(reserve(ACTOR, 1_000L, 1_001L).isReserved()).isFalse();
        assertThat(reserve(ACTOR, 1_000L, 1_000L).isReserved())
                .as("the rejected attempt held nothing")
                .isTrue();
    }

    @Test
    void aNullClockIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new InMemoryGatewayTokenBudget(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void concurrentReservationsCannotExceedTheLimit() throws InterruptedException {
        // The whole point of a reservation: 200 threads all asking for 1 token
        // against a limit of 100 at the same instant. Every thread reads the
        // same clock and the same window, so nothing but the single atomic
        // state boundary can stop them all from seeing "there is room left".
        int threads = 200;
        // The pool must hold every task at once: the ready latch counts down
        // inside each task, so a smaller pool would leave queued tasks unable to
        // signal readiness and deadlock the barrier.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (reserve(ACTOR, 100L, 1L).isReserved()) {
                        reserved.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(60, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            // Exactly the limit, never one token more: a read-then-write
            // implementation would let many threads through here.
            assertThat(reserved.get())
                    .as("only the limit's worth of tokens may be held")
                    .isEqualTo(100);
            assertThat(rejected.get()).isEqualTo(threads - 100);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReservationsOfLargerAmountsStillRespectTheTotal() throws InterruptedException {
        // Mixed sizes that cannot all fit: 60 attempts of 10 tokens against a
        // limit of 250 admits at most 25 of them, whatever order they land in.
        int threads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (reserve(ACTOR, 250L, 10L).isReserved()) {
                        reserved.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(60, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(reserved.get())
                    .as("250 tokens admits exactly 25 reservations of 10")
                    .isEqualTo(25);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReservationsForDifferentActorsDoNotShareCapacity() throws InterruptedException {
        // Ten actors, each with a limit of 10, all reserving at once: sharing a
        // single total would let one actor's spending starve another's.
        int actors = 10;
        int perActor = 10;
        int threads = actors * perActor;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        try {
            for (int actor = 0; actor < actors; actor++) {
                String subject = "actor-" + actor;
                for (int i = 0; i < perActor; i++) {
                    pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        if (reserve(subject, 10L, 1L).isReserved()) {
                            reserved.incrementAndGet();
                        }
                        return null;
                    });
                }
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(60, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(reserved.get())
                    .as("every actor gets its own full budget")
                    .isEqualTo(actors * perActor);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReservationsMintDistinctIds() throws InterruptedException {
        // Two reservations settling as one would corrupt a future
        // reconciliation, so the id must be unique even under contention.
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    ids.add(reserve(ACTOR, 1_000L, 1L).reservationId());
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            boolean terminated = pool.awaitTermination(60, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(ids).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSettledReservationIsReplacedByTheActualUsage() {
        String id = reserveId(1_000L, 100L);

        GatewayTokenBudgetReconciliation result = settle(id, 80L);

        assertThat(result.isReconciled()).isTrue();
        // The day now accounts for 80, so exactly 80 of the limit is left: a
        // 920-token reservation still fits and a 921-token one does not.
        assertThat(reserve(ACTOR, 1_000L, 920L).isReserved()).isTrue();
    }

    @Test
    void anExactSettlementAccountsForTheWholeReservation() {
        settle(reserveId(1_000L, 100L), 100L);

        // Nothing was released, so the day still carries the full 100.
        assertThat(reserve(ACTOR, 1_000L, 900L).isReserved()).isTrue();
        assertThat(reserve(ACTOR, 1_000L, 901L).isReserved()).isFalse();
    }

    @Test
    void anUnderUseSettlesBackIntoTheDaysCapacity() {
        settle(reserveId(1_000L, 100L), 20L);

        // 80 units of over-reservation are released, so the day is left owing
        // only the 20 the provider really spent.
        assertThat(reserve(ACTOR, 1_000L, 980L).isReserved()).isTrue();
        assertThat(reserve(ACTOR, 1_000L, 981L).isReserved()).isFalse();
    }

    @Test
    void anOverUseIsRecordedAsTheRealUsageAndNeverClamped() {
        String id = reserveId(1_000L, 100L);

        // The reconciliation succeeds: the provider really did spend 140, and
        // pretending it spent 100 would understate the cost.
        assertThat(settle(id, 140L).isReconciled()).isTrue();

        // The day now owes 140, so only 860 of capacity is left.
        assertThat(reserve(ACTOR, 1_000L, 860L).isReserved()).isTrue();
        assertThat(reserve(ACTOR, 1_000L, 861L).isReserved())
                .as("a request that would push the day past its limit is rejected")
                .isFalse();
    }

    @Test
    void anOverUseCanLeaveTheDayOverspentAndRejectEverythingAfterwards() {
        settle(reserveId(1_000L, 100L), 1_400L);

        // Actual usage is a post-provider fact: it is recorded as it happened
        // even when it has already passed the limit, and the day's containment
        // is expressed by refusing every later reservation.
        assertThat(reserve(ACTOR, 1_000L, 1L).isReserved()).isFalse();
        assertThat(reserve(ACTOR, 1_000L, 1L).isReserved()).isFalse();
    }

    @Test
    void zeroActualTokensIsAcceptedAndReleasesTheWholeReservation() {
        String id = reserveId(1_000L, 100L);

        // A provider can legitimately report no tokens at all, so zero is a
        // real measurement, not a missing one.
        assertThat(settle(id, 0L).isReconciled()).isTrue();
        assertThat(reserve(ACTOR, 1_000L, 1_000L).isReserved()).isTrue();
    }

    @Test
    void aSettledReservationIsSingleUse() {
        String id = reserveId(1_000L, 100L);
        settle(id, 80L);

        // A reservation may be settled exactly once: a repeat, a late retry, or
        // a duplicated callback must not credit the day a second time.
        assertThatThrownBy(() -> settle(id, 80L))
                .isInstanceOf(GatewayTokenBudgetReservationStateException.class);
        assertThat(reserve(ACTOR, 1_000L, 920L).isReserved())
                .as("the refused repeat released nothing")
                .isTrue();
    }

    @Test
    void anUnknownReservationIsRejected() {
        assertThatThrownBy(() -> settle("no-such-reservation", 10L))
                .isInstanceOf(GatewayTokenBudgetReservationStateException.class);
    }

    @Test
    void aReservationCannotBeSettledByAnotherActor() {
        String id = reserveId(1_000L, 100L);

        // Actor 2 must not be able to settle actor 1's reservation, which would
        // let it release capacity the day never gave it.
        assertThatThrownBy(() -> budget.reconcile("actor-2", DAY_START, id, 10L))
                .isInstanceOf(GatewayTokenBudgetReservationStateException.class);
        assertThatThrownBy(() -> budget.reconcile("actor-2", DAY_START, id, 0L))
                .as("even a zero-usage settlement is refused")
                .isInstanceOf(GatewayTokenBudgetReservationStateException.class);
    }

    @Test
    void aReservationCannotBeSettledOnAnotherDay() {
        String id = reserveId(1_000L, 100L);

        // One day's reservation does not belong to the next day's budget, so a
        // settlement carried out under the wrong day must fail.
        assertThatThrownBy(() -> budget.reconcile(ACTOR, NEXT_DAY_START, id, 10L))
                .isInstanceOf(GatewayTokenBudgetReservationStateException.class);
    }

    @Test
    void theReconciliationResultCarriesNoCountsOrCapacity() {
        // A settlement must not report the day it just adjusted, so the result
        // type itself cannot: success is the whole of its surface.
        assertThat(List.of(GatewayTokenBudgetReconciliation.class.getRecordComponents()))
                .extracting(component -> component.getName())
                .containsExactly("state");
    }

    @Test
    void anInvalidSettlementIsRejectedBeforeAnyStateIsTouched() {
        String id = reserveId(1_000L, 100L);

        assertThatThrownBy(() -> budget.reconcile(null, DAY_START, id, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.reconcile("   ", DAY_START, id, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.reconcile(ACTOR, null, id, 10L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> budget.reconcile(ACTOR, DAY_START.plusSeconds(1L), id, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.reconcile(ACTOR, DAY_START, null, 10L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> budget.reconcile(ACTOR, DAY_START, "   ", 10L))
                .isInstanceOf(IllegalArgumentException.class);
        // A negative usage is impossible, and would hand capacity back.
        assertThatThrownBy(() -> settle(id, -1L))
                .isInstanceOf(IllegalArgumentException.class);

        // Every one of those was refused before any read or write, so the
        // reservation is untouched and still holds its original amount.
        assertThat(reserve(ACTOR, 1_000L, 900L).isReserved()).isTrue();
    }

    @Test
    void concurrentSettlementsAndReservationsStayConsistent() throws InterruptedException {
        // Fifty reservations of 10 tokens each against a limit of 1_000, then a
        // settlement racing further reservations on the same actor and day.
        int initial = 50;
        String[] ids = new String[initial];
        for (int i = 0; i < initial; i++) {
            ids[i] = reserveId(1_000L, 10L);
        }

        int threads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settledReservations = new AtomicInteger();
        AtomicInteger duplicateAttempts = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                String id = ids[i % initial];
                long actual = 10L * (1L + (i % 3));
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        budget.reconcile(ACTOR, DAY_START, id, actual);
                        settledReservations.incrementAndGet();
                    } catch (GatewayTokenBudgetReservationStateException expected) {
                        // A repeated settlement of the same id loses the race by
                        // design; it must not be treated as a corruption.
                        duplicateAttempts.incrementAndGet();
                    }
                    // Every settlement, successful or refused, leaves an honest
                    // total, so a racing reservation is never admitted against a
                    // half-adjusted day.
                    budget.tryReserve(ACTOR, DAY_START, 1_000L, 1L);
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // Each of the 50 ids was settled at most once, no matter how many
            // threads raced for it, so the attempts partition cleanly.
            assertThat(settledReservations.get())
                    .as("no reservation is ever settled twice")
                    .isLessThanOrEqualTo(initial);
            assertThat(settledReservations.get() + duplicateAttempts.get())
                    .as("every attempt is either settled once or refused")
                    .isEqualTo(threads);

            // The strongest statement of correctness: whatever the interleaving,
            // a 1_000-token reservation can no longer fit, because the 50
            // original reservations each cost at least their 10 tokens.
            assertThat(budget.tryReserve(ACTOR, DAY_START, 1_000L, 1_000L).isReserved())
                    .isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
