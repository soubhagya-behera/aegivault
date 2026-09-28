package com.aegivault.aegivault.gateway.policy.budget;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis-backed {@link GatewayTokenBudget}, intended for multi-instance
 * enforcement: every application instance reserves against the same budget, so
 * a day's tokens are held once across the whole fleet rather than per process.
 *
 * <p><strong>One atomic server-side execution per reservation.</strong> The
 * read, the capacity comparison, and the reservation all happen inside a
 * single Lua invocation. Never a GET followed by a Java-side comparison and an
 * INCR, and never separate GET/INCR/EXPIRE round trips: a split read-then-write
 * is precisely the race this abstraction exists to close, because two
 * instances could both read the same held total, both see room, and both
 * reserve. Redis runs the script to completion without interleaving another
 * client's command, so the total a reservation is decided against already
 * includes every reservation made before it, including ones whose requests are
 * still in flight.
 *
 * <p><strong>Rejected reservations write nothing.</strong> The capacity check
 * returns before any write, so a refused attempt costs the actor nothing and a
 * client hammering a spent budget cannot push the day's total further over.
 *
 * <p>Budgets live at
 * {@code aegivault:gateway:token-budget:<windowStart>:<actorSubject>}, a
 * namespace of their own that is distinct from the request-policy counters
 * ({@code aegivault:gateway:policy-counter:...}) and from the global rate
 * limiter ({@code aegivault:gateway:rate-limit:<actorSubject>}).
 *
 * <p>Each day is one Redis hash: a {@code total} field with the day's held
 * amount, plus one {@code reservation:<id>} field per outstanding reservation
 * so a later reconciliation step can find what a given id reserved, exactly as
 * the in-memory budget can. The key carries a TTL covering the rest of its day
 * plus a clock-skew grace, so Redis expires it with no background cleanup job,
 * and a new day is a new key rather than a reset of an old one.
 *
 * <p><strong>Fail closed.</strong> Any Redis failure, or an empty or
 * unrecognised script result, surfaces as
 * {@link GatewayTokenBudgetUnavailableException} with a fixed safe message. A
 * reservation is never granted because the budget could not answer — an outage
 * must not become unlimited token spend — and no Redis host, key, actor
 * subject, token count, limit, or underlying exception text escapes this class.
 * An empty result is treated as a failure rather than as either outcome, so
 * "could not tell" is never confused with "budget spent".
 *
 * <p>Depends on nothing but Spring Data Redis and a {@link Clock}: no
 * controller, no completion service, no policy resolution, no provider, no
 * detectors, no repositories.
 */
public class RedisGatewayTokenBudget implements GatewayTokenBudget {

    /**
     * The single atomic operation behind a reservation: read the day's held
     * total, decide, and reserve — all server-side.
     *
     * <p>{@code KEYS[1]} is the one budget key for this actor and day.
     * {@code ARGV[1]} is the requested amount, {@code ARGV[2]} the daily limit,
     * {@code ARGV[3]} the TTL in milliseconds, and {@code ARGV[4]} the
     * reservation id minted by the caller so exactly one id is generated per
     * attempt and can be reported back beside the amount it holds.
     *
     * <p>The day's held total lives in the hash field {@code total}, and each
     * reservation is kept in its own field {@code reservation:<id>} holding the
     * amount it reserved, so a later reconciliation step can find the amount
     * held under a given id exactly as the in-memory budget can. Keeping the
     * total as its own field is what makes the whole thing one round trip: the
     * script reads and updates the total directly rather than summing a
     * variable number of fields.
     *
     * <p>The check precedes the write and returns immediately on failure, so a
     * rejection is guaranteed not to have created a key, applied a TTL, or
     * changed the held total.
     *
     * <p>Settled usage is deliberately not stored separately from the held
     * total: with no reconciliation step in existence it is zero, and folding
     * it into a second field would make a later reconciliation look as though
     * it already had somewhere to write.
     *
     * <p>Returns {@code 1} for a reservation and {@code 0} for a rejection.
     */
    static final DefaultRedisScript<Long> TRY_RESERVE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local held = tonumber(redis.call('HGET', KEYS[1], 'total') or '0')
                    local requested = tonumber(ARGV[1])
                    local limit = tonumber(ARGV[2])

                    if held + requested > limit then
                      return 0
                    end

                    redis.call('HSET', KEYS[1], 'total', held + requested)
                    redis.call('HSET', KEYS[1], 'reservation:' .. ARGV[4], requested)
                    if held == 0 then
                      redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]))
                    end

                    return 1
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    /** Production constructor using the system UTC clock. */
    public RedisGatewayTokenBudget(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /**
     * @param redis template bound to Spring Boot's Redis connection, never
     *        null; no I/O happens here, only per attempt
     * @param clock time source used to derive the TTL, never null
     */
    public RedisGatewayTokenBudget(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public GatewayTokenBudgetReservation tryReserve(
            String actorSubject, Instant windowStart, long limit, long requestedTokens) {
        // Validated before Redis is contacted at all: an invalid attempt must
        // not cost a round trip, create a key, or touch another actor's budget.
        String actor = GatewayTokenBudgetValidation.requireReservable(
                actorSubject, windowStart, limit, requestedTokens);

        String key = GatewayTokenBudgetWindow.DAY.keyFor(windowStart, actor);
        String[] arguments = {
            String.valueOf(requestedTokens),
            String.valueOf(limit),
            String.valueOf(GatewayTokenBudgetWindow.DAY.ttlMillis(windowStart, clock.instant())),
            UUID.randomUUID().toString()
        };

        // Exactly one script execution reads, decides, and reserves. Splitting
        // this into a read, a Java-side comparison, and an increment would
        // reintroduce the check-then-act race the script exists to close.
        final Long outcome;
        try {
            outcome = redis.execute(TRY_RESERVE_SCRIPT, List.of(key), (Object[]) arguments);
        } catch (RuntimeException ex) {
            throw new GatewayTokenBudgetUnavailableException(ex);
        }
        if (outcome == null) {
            // An empty result means the script did not answer. Reporting a
            // rejection here would claim the budget is spent when the truth is
            // that it could not be determined, so it fails closed as an error.
            throw new GatewayTokenBudgetUnavailableException(
                    new IllegalStateException("Empty token budget script result."));
        }
        if (outcome == 1L) {
            return GatewayTokenBudgetReservation.reserved(arguments[3], requestedTokens);
        }
        if (outcome == 0L) {
            // The check ran inside the script and returned before any write, so
            // nothing was reserved, no key was created, and no TTL was applied.
            return GatewayTokenBudgetReservation.rejected();
        }
        // Anything else is an unrecognised result and must not be read as
        // either outcome.
        throw new GatewayTokenBudgetUnavailableException(
                new IllegalStateException("Unrecognised token budget script result."));
    }
}