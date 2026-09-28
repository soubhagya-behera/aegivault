package com.aegivault.aegivault.gateway.policy.budget;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The token-budget window: a single fixed UTC calendar day.
 *
 * <p>Deliberately <strong>day-only</strong>. Unlike the request counter, which
 * has a minute and a day window, a token budget has only the day that
 * {@code tokensPerDay} names. There is no per-minute token window and none is
 * implied, because the policy field is a daily one.
 *
 * <p>The day semantics are the <em>same fixed UTC calendar day</em> the
 * request-policy counter already uses, derived identically
 * ({@code now.atZone(UTC).toLocalDate().atStartOfDay(UTC)}). Both
 * implementations therefore agree on when a day starts and ends, and a
 * reservation and a request count for the same actor on the same instant always
 * describe the same day. It is a fixed UTC window — never a rolling 24 hours,
 * never the JVM default zone, never "the last day".
 *
 * <p>Instances are immutable and thread-safe; the enum carries no state.
 */
public enum GatewayTokenBudgetWindow {

    /** The current UTC calendar day, {@code [start, start + 1 day)}. */
    DAY;

    /**
     * Redis key namespace for daily token budgets. A full key is
     * {@code aegivault:gateway:token-budget:<windowStart>:<actorSubject>}, for
     * example {@code aegivault:gateway:token-budget:1773532800:actor-1}.
     *
     * <p><strong>Distinct from every other gateway namespace.</strong> The
     * token-budget segment {@code token-budget} appears in no other key, so
     * these keys can never collide with the request-policy counters
     * ({@code aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>})
     * or with the global rate limiter
     * ({@code aegivault:gateway:rate-limit:<actorSubject>}). The two gateway
     * mechanisms guard different quantities of different things — tokens versus
     * requests — and sharing a key would let one silently corrupt the other.
     *
     * <p>The format is deterministic: the same actor and day always map to the
     * same key, so the epoch-second day start means a new day is a new key
     * rather than a reset of an old one. The actor segment is the verified JWT
     * subject (server-issued UUIDs in this project) — never an IP address,
     * model name, or request content. Redis keys are binary-safe, so the
     * subject needs no escaping.
     */
    public static final String KEY_PREFIX = "aegivault:gateway:token-budget";

    /**
     * Extra lifetime added to a key's TTL beyond the end of its day.
     *
     * <p>The application decides day boundaries, but Redis decides expiry, and
     * the two clocks are not the same machine. Without a grace period a key
     * could expire a moment before its day has actually ended on the Redis
     * side, and a reservation in that sliver would read a freshly emptied
     * budget and be admitted early. One second is far larger than normal clock
     * skew and still short relative to a day.
     */
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(1);

    /**
     * Returns the inclusive start of the UTC day containing {@code now}.
     *
     * @param now the instant to place in a day, never null
     * @return the UTC day start, never null
     */
    public Instant windowStart(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Returns the exclusive end of the day starting at {@code dayStart}.
     *
     * @param dayStart an inclusive UTC day start, never null
     * @return the day end, never null
     */
    public Instant windowEnd(Instant dayStart) {
        return dayStart.plus(1L, ChronoUnit.DAYS);
    }

    /** The full length of one day window. */
    public Duration length() {
        return Duration.ofDays(1);
    }

    /**
     * Whether {@code instant} is exactly a UTC day start.
     *
     * <p>Used to reject a partial-day window start before any state is touched.
     * An unaligned start would name a window that matches no real UTC day and
     * would produce a key no other caller derives, silently splitting one day's
     * budget into two independent budgets.
     *
     * @param instant the candidate day start, never null
     * @return whether it is exactly midnight UTC
     */
    public boolean isAlignedDayStart(Instant instant) {
        return instant.equals(windowStart(instant));
    }

    /**
     * Returns how long the budget key for {@code dayStart} should live, so it
     * disappears on its own shortly after its day ends.
     *
     * <p>No cleanup job, sweep, or scheduler is involved: expiry is the storage
     * layer's own mechanism, exactly as Redis TTL is for the other gateway
     * counters.
     *
     * @param dayStart the inclusive day start, never null
     * @param now the instant the reservation happens at, never null
     * @return a strictly positive TTL in milliseconds
     */
    public long ttlMillis(Instant dayStart, Instant now) {
        long remaining = windowEnd(dayStart).toEpochMilli() - now.toEpochMilli();
        return Math.max(1L, remaining) + EXPIRY_GRACE.toMillis();
    }

    /**
     * Returns the deterministic namespaced budget key for one actor on one day.
     *
     * @param dayStart an inclusive UTC day start, never null
     * @param actorSubject verified JWT subject, never blank
     * @return the namespaced key, never null
     */
    public String keyFor(Instant dayStart, String actorSubject) {
        return KEY_PREFIX + ":" + dayStart.getEpochSecond() + ":" + actorSubject;
    }
}