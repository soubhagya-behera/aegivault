package com.aegivault.aegivault.gateway.policy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis-backed {@link GatewayUsagePolicyCounter}, intended for multi-instance
 * enforcement: every application instance consults and increments the same
 * counter, so a policy limit is respected across the whole fleet rather than
 * per process.
 *
 * <p><strong>One atomic server-side execution per attempt.</strong> The
 * increment, the limit comparison, and the TTL are a single Lua script
 * invocation — never a GET followed by an INCR followed by an EXPIRE. Redis
 * runs a script to completion without interleaving other commands, so two
 * concurrent requests for the same actor and window are serialized: the
 * second one sees the count left by the first and cannot be admitted on a
 * stale read. Separate application-side round trips would reintroduce exactly
 * the check-then-act race this abstraction exists to close.
 *
 * <p>Counters are namespaced per
 * {@link GatewayUsagePolicyCounterWindow#KEY_PREFIX} and carry the window
 * start, so they are distinct from the global gateway rate limiter's keys and
 * a new window is a new key rather than a reset of an old one. Each key is
 * created with a TTL covering the rest of its window, so Redis expires it on
 * its own; there is no background cleanup job.
 *
 * <p><strong>Fail closed.</strong> Any Redis failure, or an empty script
 * result, surfaces as {@link GatewayUsagePolicyCounterUnavailableException}
 * with a fixed safe message. A request is never admitted because the counter
 * could not answer, and no Redis host, key, counter value, actor subject, or
 * underlying exception text escapes this class. An empty result is treated
 * as a failure rather than as a rejection so that "unknown" is never confused
 * with "limit spent".
 *
 * <p>Depends on nothing but Spring Data Redis and a {@link Clock}: no
 * controller, no completion service, no policy resolution, no provider, no
 * detectors, no repositories.
 */
public class RedisGatewayUsagePolicyCounter implements GatewayUsagePolicyCounter {

    /**
     * The single atomic operation behind every consume: increment, set the TTL
     * on first creation only, then compare against the limit.
     *
     * <p>The limit arrives as {@code ARGV[1]} and the TTL in milliseconds as
     * {@code ARGV[2]}; the key is {@code KEYS[1]}. Returning {@code 1} admits
     * the request and {@code 0} rejects it. The comparison is
     * {@code current <= limit}, which is {@code currentCount + 1 <= limit}
     * after the increment — a limit is the highest permitted value.
     */
    static final DefaultRedisScript<Long> TRY_CONSUME_SCRIPT = tryConsumeScript();

    private final StringRedisTemplate redis;

    private final Clock clock;

    private static DefaultRedisScript<Long> tryConsumeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(String.join("\n",
                "local current = redis.call('INCR', KEYS[1])",
                "if current == 1 then",
                "  redis.call('PEXPIRE', KEYS[1], ARGV[2])",
                "end",
                "if current <= tonumber(ARGV[1]) then",
                "  return 1",
                "else",
                "  return 0",
                "end"));
        script.setResultType(Long.class);
        return script;
    }

    /** Production constructor using the system UTC clock. */
    public RedisGatewayUsagePolicyCounter(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /**
     * @param redis template bound to Spring Boot's Redis connection, never
     *        null; no I/O happens here, only per attempt
     * @param clock time source used to derive the window, never null
     */
    public RedisGatewayUsagePolicyCounter(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
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
        String key = window.keyFor(window.windowStart(now), actor);
        final Long allowed;
        try {
            allowed = redis.execute(
                    TRY_CONSUME_SCRIPT, List.of(key), String.valueOf(limit), String.valueOf(window.ttlMillis(now)));
        } catch (RuntimeException ex) {
            throw new GatewayUsagePolicyCounterUnavailableException(ex);
        }
        if (allowed == null) {
            // An empty result means the script did not answer. Treating that
            // as a rejection would report "limit spent" for what is really
            // "could not tell", so it fails closed as an error instead.
            throw new GatewayUsagePolicyCounterUnavailableException(
                    new IllegalStateException("Empty policy counter script result."));
        }
        return allowed == 1L;
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
