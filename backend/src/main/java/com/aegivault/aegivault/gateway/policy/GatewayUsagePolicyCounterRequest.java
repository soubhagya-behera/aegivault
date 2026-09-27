package com.aegivault.aegivault.gateway.policy;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One atomic request to consume request capacity across one or more fixed UTC
 * windows for a single actor.
 *
 * <p>It is the input to the counter's multi-window operation, and it is a
 * typed record rather than a map or a list of pairs: a request names its
 * actor, its instant, and at most one limit per window, so "consume one unit
 * from the day and one from the minute, or neither" is expressible as a
 * single value instead of a sequence of calls that could half-apply.
 *
 * <p><strong>Why one request instead of several calls.</strong> Consuming the
 * day limit and then the minute limit in two steps means the first step's
 * capacity is already spent when the second rejects. The request makes the
 * whole set of limits one unit of work: either every requested window has
 * room and all of them are incremented, or none is touched at all.
 *
 * <p><strong>Policy-independent.</strong> This type knows an actor, an instant,
 * a window, and a limit. It does not know {@link GatewayUsagePolicy}, does not
 * resolve or evaluate one, and has no notion of tokens, costs, or budgets.
 * Which windows a given policy needs is decided by the caller, not here.
 *
 * <p><strong>Duplicate windows are impossible</strong>: the limits live in an
 * {@link EnumMap} keyed by window, so a window can appear at most once and a
 * duplicate cannot be expressed. At least one limit is required — a request
 * that asks to consume nothing would have nothing to decide, and silently
 * returning ALLOWED for it would report an admission that never happened.
 *
 * <p>The map is unmodifiable and its iteration order is
 * {@link GatewayUsagePolicyCounterWindow#evaluationRank()} (day, then
 * minute), never the map's own order, so a multi-window attempt is fully
 * deterministic.
 *
 * @param actorSubject verified JWT subject, never blank; trimmed exactly
 *        like the rest of the policy package trims it
 * @param now the instant the attempt happens at, never null; each requested
 *        window is resolved from it, never from an internal clock
 * @param limits per-window limits, at least one entry, each limit strictly
 *        positive, no null window or limit
 * @throws IllegalArgumentException when the actor is blank, or when a limit
 *         is not strictly positive
 * @throws NullPointerException when {@code now} or {@code limits} is null
 */
public record GatewayUsagePolicyCounterRequest(
        String actorSubject, Instant now, Map<GatewayUsagePolicyCounterWindow, Long> limits) {

    public GatewayUsagePolicyCounterRequest {
        actorSubject = requireActor(actorSubject);
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (limits.isEmpty()) {
            throw new IllegalArgumentException("limits must not be empty");
        }
        limits = Map.copyOf(limits);
        limits.forEach((window, limit) -> {
            Objects.requireNonNull(window, "window must not be null");
            if (limit == null || limit <= 0L) {
                throw new IllegalArgumentException("limit must be positive");
            }
        });
    }

    /** A request consuming one unit from a single window. */
    public static GatewayUsagePolicyCounterRequest of(
            String actorSubject, Instant now, GatewayUsagePolicyCounterWindow window, long limit) {
        // Validated before the map is built: Map.of would otherwise reject a
        // null window with a message-less NullPointerException.
        Objects.requireNonNull(window, "window must not be null");
        return new GatewayUsagePolicyCounterRequest(actorSubject, now, Map.of(window, limit));
    }

    /** A request consuming one unit from both the day and the minute window. */
    public static GatewayUsagePolicyCounterRequest ofBoth(
            String actorSubject, Instant now, long perDay, long perMinute) {
        EnumMap<GatewayUsagePolicyCounterWindow, Long> both = new EnumMap<>(GatewayUsagePolicyCounterWindow.class);
        both.put(GatewayUsagePolicyCounterWindow.DAY, perDay);
        both.put(GatewayUsagePolicyCounterWindow.MINUTE, perMinute);
        return new GatewayUsagePolicyCounterRequest(actorSubject, now, both);
    }

    /**
     * The requested windows in the fixed evaluation order (day, then minute).
     *
     * @return an unmodifiable, deterministically ordered list
     */
    public List<GatewayUsagePolicyCounterWindow> windowsInEvaluationOrder() {
        return limits.keySet().stream()
                .sorted(java.util.Comparator.comparingInt(GatewayUsagePolicyCounterWindow::evaluationRank))
                .toList();
    }

    /**
     * The namespaced counter key for one requested window.
     *
     * @param window a requested window, never null
     * @return the deterministic key for this actor in that window, never null
     */
    public String keyFor(GatewayUsagePolicyCounterWindow window) {
        return window.keyFor(window.windowStart(now), actorSubject);
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
