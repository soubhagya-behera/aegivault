package com.aegivault.aegivault.gateway.policy;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local, thread-safe implementation of
 * {@link GatewayUsagePolicyCounter} for deterministic local development and
 * testing.
 *
 * <p>Each admitted request is counted through exactly one atomic
 * {@link ConcurrentHashMap#compute} call per attempt on a
 * {@code (window, actor, windowStart)} key, and the read-increment-compare
 * sequence happens entirely inside that single map operation. No lock is
 * taken and no check-then-act gap exists, so simultaneous requests for one
 * actor and window cannot overshoot the limit: at most {@code limit} of them
 * can observe an admitting count.
 *
 * <p><strong>Deliberately separate from the rate limiter.</strong> This class
 * shares no internals, no state, and no constants with
 * {@code InMemoryGatewayRateLimiter} — not its map, not its eviction sweep,
 * not its window arithmetic. They answer different questions about different
 * state: that limiter guards a fixed global gateway quota unrelated to any
 * policy, while this one enforces an owner-scoped policy limit. Merging them
 * would couple policy enforcement to the global limiter's lifecycle and
 * window definition, and would let a change to one silently alter the other.
 * The only thing they share is the {@link GatewayUsagePolicyCounter}
 * abstraction and {@link GatewayUsagePolicyCounterWindow}.
 *
 * <p><strong>Local only.</strong> This state lives in one JVM heap: it is not
 * shared between application instances, so a multi-instance deployment needs
 * {@link RedisGatewayUsagePolicyCounter} to admit a request only once across
 * the fleet. Expired windows are dropped when a newer window is observed for
 * the same key, so an idle actor's entry does not linger; there is no
 * scheduler, no Redis, and no persistence.
 *
 * <p>Depends on nothing but a {@link Clock}: no controller, no completion
 * service, no policy resolution, no provider, no detectors, no
 * repositories.
 */
public class InMemoryGatewayUsagePolicyCounter implements GatewayUsagePolicyCounter {

    private final Clock clock;

    private final ConcurrentHashMap<CounterKey, long[]> counts = new ConcurrentHashMap<>();

    private record CounterKey(GatewayUsagePolicyCounterWindow window, String actorSubject, Instant windowStart) {}

    /** Production constructor using the system UTC clock. */
    public InMemoryGatewayUsagePolicyCounter() {
        this(Clock.systemUTC());
    }

    /**
     * Constructor with an explicit clock, so tests can move time
     * deterministically instead of sleeping.
     *
     * @param clock time source, never null
     */
    public InMemoryGatewayUsagePolicyCounter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean tryConsume(
            String actorSubject, GatewayUsagePolicyCounterWindow window, long limit) {
        String actor = requireActor(actorSubject);
        Objects.requireNonNull(window, "window must not be null");
        if (limit <= 0L) {
            throw new IllegalArgumentException("limit must be positive");
        }

        Instant now = clock.instant();
        // A new window is a distinct key, so an old window's count cannot
        // bleed into the new one and no in-place reset is needed.
        CounterKey key = new CounterKey(window, actor, window.windowStart(now));
        boolean[] allowed = new boolean[1];

        // One atomic map operation encloses the read, the increment, and the
        // comparison. ConcurrentHashMap.compute holds the bin lock for this
        // key, so two threads can never both read the same count and both
        // conclude there is room.
        counts.compute(key, (ignored, existing) -> {
            long current = existing == null ? 0L : existing[0];
            if (current + 1L <= limit) {
                allowed[0] = true;
                return new long[] {current + 1L};
            }
            allowed[0] = false;
            // Rejected attempts do not consume: the count stays equal to the
            // number of admitted requests, so a client hammering a spent
            // limit cannot inflate the counter it is already over.
            return existing;
        });

        evictSupersededWindows(window, key);
        return allowed[0];
    }

    private void evictSupersededWindows(GatewayUsagePolicyCounterWindow window, CounterKey current) {
        counts.keySet().removeIf(existing -> existing.window() == window
                && !existing.windowStart().equals(current.windowStart())
                && existing.actorSubject().equals(current.actorSubject()));
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
