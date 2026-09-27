package com.aegivault.aegivault.gateway.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The two request windows an owner-scoped gateway usage policy can be
 * enforced against: the current UTC minute and the current UTC calendar day.
 *
 * <p>Windows are <em>fixed</em> and always UTC — never the JVM default zone,
 * never a rolling window, and never "the last 60 seconds". A fixed window is
 * what makes the count a plain counter that both the in-memory and the Redis
 * implementation can hold, and the UTC alignment is what makes two instances
 * agree on when a window starts and ends.
 *
 * <p><strong>Tokens are deliberately absent.</strong> {@code tokensPerDay} is
 * a supported policy field but has no window here. Token usage is only known
 * <em>after</em> a provider response, while request admission happens
 * <em>before</em> provider invocation, so there is no truthful token number to
 * compare at admission time. This milestone therefore enforces request limits
 * only, and adding a token window later would require an explicit
 * reservation/accounting design rather than an estimate. No character-to-token
 * conversion, max-token assumption, or response-size heuristic is used
 * anywhere in this package.
 *
 * <p>Instances are immutable and thread-safe; the enum carries no state.
 */
public enum GatewayUsagePolicyCounterWindow {

    /** The current UTC minute, {@code [start, start + 1 minute)}. */
    MINUTE("minute", ChronoUnit.MINUTES),

    /** The current UTC calendar day, {@code [start, start + 1 day)}. */
    DAY("day", ChronoUnit.DAYS);

    /**
     * Redis key namespace for the policy request counters. A full key is
     * {@code aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>},
     * for example
     * {@code aegivault:gateway:policy-counter:minute:1773578400:actor-1}.
     *
     * <p>The format is deterministic — the same actor in the same window
     * always maps to the same key — and it is namespaced so it can never
     * collide with the separate global gateway rate limiter's
     * {@code aegivault:gateway:rate-limit:<actorSubject>} keys: the segment
     * {@code policy-counter} appears in a policy key and never in a rate-limit
     * key. The window segment keeps minute and day counters apart, and the
     * epoch-second window start means a new window is a new key rather than a
     * reset of an old one. The actor segment is the verified JWT subject
     * (server-issued UUIDs in this project) — never an IP address, model
     * name, or request content. Redis keys are binary-safe, so the subject
     * needs no escaping.
     */
    public static final String KEY_PREFIX = "aegivault:gateway:policy-counter";

    /**
     * Extra lifetime added to a key's TTL beyond the end of its window.
     *
     * <p>The application decides window boundaries, but Redis decides
     * expiry, and the two clocks are not the same machine. Without a grace
     * period a key could expire a moment before the window it belongs to has
     * actually ended on the Redis side, and a request in that sliver would
     * read a freshly reset counter and be admitted a little early. One
     * second is far larger than normal clock skew and still short relative to
     * a minute or a day.
     */
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(1);

    private final String keySegment;

    private final ChronoUnit unit;

    GatewayUsagePolicyCounterWindow(String keySegment, ChronoUnit unit) {
        this.keySegment = keySegment;
        this.unit = unit;
    }
    /**
     * The deterministic position of this window when several are checked
     * together. Lower ranks are checked (and therefore reported) first.
     *
     * <p>The order is fixed by this method, not derived from configured limit
     * values, map iteration order, or timestamps: which window rejects first
     * depends on runtime counts, not on what a policy happens to declare, so
     * no data-dependent ordering could be both useful and deterministic. The
     * order only decides <em>which</em> exhausted window is named in a
     * rejection; the accept/reject outcome itself is order-independent.
     */
    public int evaluationRank() {
        return switch (this) {
            case DAY -> 0;
            case MINUTE -> 1;
        };
    }

    /**
     * Returns the inclusive start of the window containing {@code now}.
     *
     * @param now the instant to place in a window, never null
     * @return the window start, never null
     */
    public Instant windowStart(Instant now) {
        return switch (this) {
            // A UTC minute boundary and a UTC day boundary are the same
            // instants; deriving the day explicitly keeps the two rules
            // readable and independent of each other.
            case MINUTE -> now.truncatedTo(ChronoUnit.MINUTES);
            case DAY -> now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        };
    }

    /**
     * Returns the exclusive end of the window starting at {@code windowStart}.
     *
     * @param windowStart an inclusive window start, never null
     * @return the window end, never null
     */
    public Instant windowEnd(Instant windowStart) {
        return windowStart.plus(1L, unit);
    }

    /** The full length of one window. */
    public Duration length() {
        return switch (this) {
            case MINUTE -> Duration.ofMinutes(1);
            case DAY -> Duration.ofDays(1);
        };
    }

    /**
     * Returns how long the counter key for {@code now} should live, so it
     * disappears on its own shortly after the window it belongs to ends.
     *
     * <p>No cleanup job, sweep, or scheduler is involved: expiry is the
     * storage layer's own mechanism, exactly as Redis TTL is for the global
     * rate limiter.
     *
     * @param now the instant the consume happens at, never null
     * @return a strictly positive TTL in milliseconds
     */
    public long ttlMillis(Instant now) {
        long remaining = windowEnd(windowStart(now)).toEpochMilli() - now.toEpochMilli();
        return Math.max(1L, remaining) + EXPIRY_GRACE.toMillis();
    }

    /**
     * Returns the deterministic namespaced counter key for one actor in one
     * window.
     *
     * @param windowStart an inclusive window start, never null
     * @param actorSubject verified JWT subject, never blank
     * @return the namespaced key, never null
     */
    public String keyFor(Instant windowStart, String actorSubject) {
        return KEY_PREFIX + ":" + keySegment + ":" + windowStart.getEpochSecond() + ":" + actorSubject;
    }
}
