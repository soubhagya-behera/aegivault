package com.aegivault.aegivault.gateway.policy.budget;

import java.time.Instant;

/**
 * The one place every {@link GatewayTokenBudget} implementation validates its
 * arguments, so the in-memory and Redis budgets cannot drift apart on what
 * counts as a legal reservation.
 *
 * <p>All of these checks run <strong>before any state is read or written</strong>
 * and before any store is contacted. An invalid argument is a programming
 * error, not a budget outcome, so it must never consume capacity, create a key,
 * or cost a round trip.
 *
 * <p>Package-private on purpose: this is an implementation detail of the
 * budget implementations, not API a caller can invoke.
 */
final class GatewayTokenBudgetValidation {

    private GatewayTokenBudgetValidation() {
        // Utility holder.
    }

    /**
     * Validates one reservation attempt and returns the trimmed actor subject.
     *
     * @param actorSubject verified JWT subject, never blank
     * @param windowStart inclusive UTC day start, never null and never partial
     * @param limit strictly positive daily token limit
     * @param requestedTokens strictly positive amount to reserve
     * @return the trimmed actor subject to account against
     * @throws IllegalArgumentException when the actor is blank, the day start
     *         is not midnight UTC, the limit is not positive, or the requested
     *         amount is not positive
     * @throws NullPointerException when the day start is null
     */
    static String requireReservable(
            String actorSubject, Instant windowStart, long limit, long requestedTokens) {
        String actor = requireActor(actorSubject);
        requireDayStart(windowStart);
        requireLimit(limit);
        requireRequestedTokens(requestedTokens);
        return actor;
    }

    /**
     * @param actorSubject verified JWT subject, never blank
     * @return the trimmed subject
     */
    static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        // Trimmed exactly like the rest of the policy package, so an untrimmed
        // spelling of one actor cannot start a second budget for that actor.
        return actorSubject.trim();
    }

    /**
     * @param windowStart inclusive UTC day start, never null
     */
    static void requireDayStart(Instant windowStart) {
        if (windowStart == null) {
            throw new NullPointerException("windowStart must not be null");
        }
        if (!GatewayTokenBudgetWindow.DAY.isAlignedDayStart(windowStart)) {
            // A partial-day start names no real UTC day: it would mint a key no
            // other caller derives and silently split one day's budget in two.
            throw new IllegalArgumentException("windowStart must be a UTC day start");
        }
    }

    /**
     * @param limit strictly positive daily token limit
     */
    static void requireLimit(long limit) {
        if (limit <= 0L) {
            // A non-positive limit is a contradiction, not a limit: nothing
            // could ever satisfy the capacity check, so accepting it would
            // quietly mean "deny every token" rather than express an intent.
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    /**
     * @param requestedTokens strictly positive amount to reserve
     */
    static void requireRequestedTokens(long requestedTokens) {
        if (requestedTokens <= 0L) {
            // Reserving zero or a negative amount would report a successful
            // reservation while holding nothing, and a negative amount would
            // hand capacity back on every call.
            throw new IllegalArgumentException("requestedTokens must be positive");
        }
    }
}