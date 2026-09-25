package com.aegivault.aegivault.gateway;

import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis-backed fixed-window gateway rate limiter for multi-instance
 * enforcement, behind the same {@link GatewayRateLimiter} abstraction as
 * the process-local implementation. Each actor owns one counter at
 * {@code aegivault:gateway:rate-limit:<actorSubject>} (see
 * {@link GatewayRateLimitPolicy#keyFor}), counting
 * {@link GatewayRateLimitPolicy#MAX_REQUESTS} completions per
 * {@link GatewayRateLimitPolicy#WINDOW}.
 *
 * <p>Every attempt is exactly one atomic server-side Lua execution —
 * increment, set the window TTL only on first creation, and compare
 * against the limit — never separate GET/INCR/EXPIRE round trips, so
 * concurrent requests cannot overshoot the limit. The key expires
 * automatically after the window; there is no background scheduler.
 *
 * <p>Fail-closed: any Redis failure (or an empty script result) surfaces
 * as {@link GatewayRateLimitUnavailableException} with a generic message
 * — requests never silently bypass rate limiting, and no Redis host,
 * exception text, key, or counter ever leaves this class.
 *
 * <p>Depends on nothing but Spring Data Redis: no controller, no
 * completion service, no provider, no detectors, no repositories.
 */
public class RedisGatewayRateLimiter implements GatewayRateLimiter {

    static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = fixedWindowScript();

    private final StringRedisTemplate redis;

    private static DefaultRedisScript<Long> fixedWindowScript() {
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

    /**
     * @param redis template bound to Spring Boot's Redis connection,
     *        never null; no I/O happens here, only per attempt
     */
    public RedisGatewayRateLimiter(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
    }

    @Override
    public boolean tryAcquire(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        final Long allowed;
        try {
            allowed = redis.execute(
                    FIXED_WINDOW_SCRIPT,
                    List.of(GatewayRateLimitPolicy.keyFor(actorSubject)),
                    String.valueOf(GatewayRateLimitPolicy.MAX_REQUESTS),
                    String.valueOf(GatewayRateLimitPolicy.windowMillis()));
        } catch (RuntimeException ex) {
            throw new GatewayRateLimitUnavailableException(ex);
        }
        if (allowed == null) {
            throw new GatewayRateLimitUnavailableException(
                    new IllegalStateException("Empty rate-limit script result."));
        }
        return allowed == 1L;
    }
}
