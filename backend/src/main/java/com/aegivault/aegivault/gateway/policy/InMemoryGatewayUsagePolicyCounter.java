package com.aegivault.aegivault.gateway.policy;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
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
 * <p><strong>Multi-window attempts are atomic per actor.</strong> State is
 * keyed by actor, with each actor's per-window counts held in a single value,
 * so one {@link ConcurrentHashMap#compute} covers the rollover check, every
 * capacity check, and every increment of a multi-window attempt. The
 * implementation deliberately never issues two independent
 * {@code compute} calls for one request: that would reintroduce exactly the
 * partial consumption the atomic multi-window operation exists to prevent.
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

    /**
     * One entry per actor, holding that actor's live per-window counts.
     *
     * <p>Keying by actor rather than by {@code (window, actor, start)} is
     * what makes a multi-window attempt atomic: every window an actor has is
     * inside a single value, so one {@link ConcurrentHashMap#compute} covers
     * them all. With per-window keys, checking two windows would need two
     * independent operations and could half-apply.
     */
    private final ConcurrentHashMap<String, EnumMap<GatewayUsagePolicyCounterWindow, WindowCount>> counts =
            new ConcurrentHashMap<>();

    /** One window's count and the window it belongs to. */
    private record WindowCount(Instant windowStart, long count) {}

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
        return tryConsume(GatewayUsagePolicyCounterRequest.of(actorSubject, clock.instant(), window, limit))
                .isAllowed();
    }

    @Override
    public GatewayUsagePolicyCounterResult tryConsume(GatewayUsagePolicyCounterRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        // The request's own instant drives window selection, so a caller
        // controls the windows deterministically; the clock is only used by
        // the convenience single-window overload.
        Instant now = request.now();
        // Evaluation order is fixed by the request (day, then minute), never by
        // map iteration order or limit values.
        List<GatewayUsagePolicyCounterWindow> windows = request.windowsInEvaluationOrder();
        GatewayUsagePolicyCounterResult[] outcome = new GatewayUsagePolicyCounterResult[1];

        // A single atomic map operation encloses every window's rollover, every
        // capacity check, and every increment. ConcurrentHashMap.compute holds
        // the bin lock for this actor, so two threads can never interleave a
        // "check both, then increment both" and half-apply a multi-window
        // attempt.
        counts.compute(request.actorSubject(), (ignored, existing) -> {
            EnumMap<GatewayUsagePolicyCounterWindow, WindowCount> state = rolled(existing, windows, now);

            // Check every window before touching any of them: a rejection
            // must leave all counts exactly as they were, so a refused request
            // never costs the actor capacity in a sibling window.
            for (GatewayUsagePolicyCounterWindow window : windows) {
                if (state.get(window).count() + 1L > request.limits().get(window)) {
                    outcome[0] = GatewayUsagePolicyCounterResult.rejected(window);
                    return state;
                }
            }

            // All windows had room: increment them together.
            windows.forEach(window -> {
                WindowCount current = state.get(window);
                state.put(window, new WindowCount(current.windowStart(), current.count() + 1L));
            });
            outcome[0] = GatewayUsagePolicyCounterResult.allowed();
            return state;
        });

        evictSupersededWindows(request.actorSubject(), now);
        return outcome[0];
    }

    /**
     * Returns the actor's live counts for {@code windows}, resetting any
     * window whose start no longer matches {@code now}.
     */
    private static EnumMap<GatewayUsagePolicyCounterWindow, WindowCount> rolled(
            EnumMap<GatewayUsagePolicyCounterWindow, WindowCount> existing,
            List<GatewayUsagePolicyCounterWindow> windows,
            Instant now) {
        EnumMap<GatewayUsagePolicyCounterWindow, WindowCount> state = new EnumMap<>(GatewayUsagePolicyCounterWindow.class);
        if (existing != null) {
            state.putAll(existing);
        }
        for (GatewayUsagePolicyCounterWindow window : windows) {
            Instant start = window.windowStart(now);
            WindowCount current = state.get(window);
            if (current == null || !current.windowStart().equals(start)) {
                // A new window starts from zero; an old count can never bleed
                // into the new window.
                state.put(window, new WindowCount(start, 0L));
            }
        }
        return state;
    }

    private void evictSupersededWindows(String actorSubject, Instant now) {
        // An actor idle since an earlier window holds no useful state, so its
        // stale windows are dropped rather than accumulating. Only that
        // actor's entry is touched; others keep their own live windows.
        counts.computeIfPresent(actorSubject, (ignored, state) -> {
            EnumMap<GatewayUsagePolicyCounterWindow, WindowCount> kept = new EnumMap<>(GatewayUsagePolicyCounterWindow.class);
            state.forEach((window, windowCount) -> {
                if (window.windowStart(now).equals(windowCount.windowStart())) {
                    kept.put(window, windowCount);
                }
            });
            return kept.isEmpty() ? null : kept;
        });
    }
}
