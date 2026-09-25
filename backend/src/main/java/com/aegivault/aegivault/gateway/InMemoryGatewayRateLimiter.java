package com.aegivault.aegivault.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local fixed-window gateway rate limiter keyed by the verified
 * JWT subject: each actor gets {@link #MAX_REQUESTS} completions per
 * {@link #WINDOW}, counted in windows aligned to the epoch. Thread-safe
 * through one atomic {@link ConcurrentHashMap#compute} per attempt, so
 * simultaneous requests for one actor cannot overshoot the limit.
 *
 * <p>Expired windows are evicted lazily — at most one sweep per window,
 * taken on the first attempt observed in that window — so idle actors
 * never accumulate. There is no
 * Redis, no persistence, and no background scheduler: this limiter is
 * process-local, is not shared between application instances, and is
 * not distributed rate limiting. The Redis-backed implementation
 * ({@link RedisGatewayRateLimiter}) serves multi-instance enforcement
 * behind the same {@link GatewayRateLimiter} abstraction.
 *
 * <p>Policy values live in {@link GatewayRateLimitPolicy} and are aliased
 * here only so existing references keep compiling; the policy class is
 * the single source of truth.
 *
 * <p>Depends on nothing but a {@link Clock}: no controller, no
 * completion service, no provider, no detectors, no repositories. Wired
 * by {@link RateLimiterConfiguration}, never component-scanned, so
 * exactly one {@link GatewayRateLimiter} bean exists per configuration.
 */
public class InMemoryGatewayRateLimiter implements GatewayRateLimiter {

    /** Allowed completions per actor per window (see {@link GatewayRateLimitPolicy}). */
    public static final int MAX_REQUESTS = GatewayRateLimitPolicy.MAX_REQUESTS;

    /** Fixed window length (see {@link GatewayRateLimitPolicy}). */
    public static final Duration WINDOW = GatewayRateLimitPolicy.WINDOW;

    private final Clock clock;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private volatile long sweptWindowStart = Long.MIN_VALUE;

    private record Window(long windowStartEpochMillis, int count) {}

    /** Production constructor using the system clock. */
    public InMemoryGatewayRateLimiter() {
        this(Clock.systemUTC());
    }

    /**
     * Constructor with an explicit clock, so tests can move time
     * deterministically instead of sleeping.
     *
     * @param clock time source, never null
     */
    public InMemoryGatewayRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean tryAcquire(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        long windowStart = clock.millis() - (clock.millis() % WINDOW.toMillis());
        boolean[] allowed = new boolean[1];
        windows.compute(actorSubject, (actor, existing) -> {
            if (existing == null || existing.windowStartEpochMillis() != windowStart) {
                allowed[0] = true;
                return new Window(windowStart, 1);
            }
            if (existing.count() < MAX_REQUESTS) {
                allowed[0] = true;
                return new Window(windowStart, existing.count() + 1);
            }
            allowed[0] = false;
            return existing;
        });
        evictIfWindowChanged(windowStart);
        return allowed[0];
    }

    private void evictIfWindowChanged(long windowStart) {
        if (windowStart != sweptWindowStart) {
            evictExpiredWindows(windowStart);
            sweptWindowStart = windowStart;
        }
    }

    private void evictExpiredWindows(long currentWindowStart) {
        windows.entrySet().removeIf(entry -> entry.getValue().windowStartEpochMillis() != currentWindowStart);
    }

    /**
     * Actors currently holding window state. Visible for testing the
     * eviction strategy only — never part of the limiter contract.
     */
    Set<String> trackedActors() {
        return Set.copyOf(windows.keySet());
    }
}
