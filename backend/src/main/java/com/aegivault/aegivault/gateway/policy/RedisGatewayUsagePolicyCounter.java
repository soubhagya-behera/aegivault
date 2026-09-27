package com.aegivault.aegivault.gateway.policy;

import java.time.Clock;
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
 * <p><strong>One atomic server-side execution per attempt, for any number of
 * windows.</strong> A multi-window
 * {@link GatewayUsagePolicyCounterRequest} is decided and consumed by a single
 * Lua invocation: it reads every requested counter, decides, and only then
 * increments. Never separate GET/INCR/EXPIRE round trips, and never one
 * script per window, so concurrent requests cannot overshoot a limit and a
 * rejection cannot leave one window consumed and another untouched. Each key
 * is created with a TTL covering the rest of its window, so Redis expires it
 * on its own; there is no background cleanup job.
 *
 * Counters stay namespaced per
 * {@link GatewayUsagePolicyCounterWindow#KEY_PREFIX} and keep carrying the
 * window start, so they remain distinct from the global gateway rate
 * limiter's keys and a new window is a new key rather than a reset of an old
 * one — the documented external key shape is unchanged by this contract
 * upgrade.
 *
 * <p><strong>Fail closed.</strong> Any Redis failure, or an empty script
 * result, surfaces as {@link GatewayUsagePolicyCounterUnavailableException}
 * with a fixed safe message. A request is never admitted because the counter
 * could not answer, and no Redis host, key, counter value, actor subject, or
 * underlying exception text escapes this class. An empty or unrecognised
 * result is treated as a failure rather than as either outcome, so "unknown"
 * is never confused with "allowed" or with "limit spent".
 *
 * <p>Depends on nothing but Spring Data Redis and a {@link Clock}: no
 * controller, no completion service, no policy resolution, no provider, no
 * detectors, no repositories.
 */
public class RedisGatewayUsagePolicyCounter implements GatewayUsagePolicyCounter {

    /**
     * The single atomic operation behind a multi-window consume: read every
     * requested counter, decide, then increment — all server-side.
     *
     * <p>{@code KEYS[1..n]} are the requested windows' keys in the caller's
     * fixed evaluation order. Each window contributes two arguments: its limit
     * and its TTL in milliseconds ({@code ARGV[2i-1]}, {@code ARGV[2i]}).
     *
     * <p>The two-phase structure is what removes partial consumption. Phase
     * one only <em>reads</em> every key and returns on the first window that
     * would be exceeded, so nothing has been written when a rejection is
     * decided. Phase two runs only when every window had room, and it then
     * increments them all. A rejection can therefore never leave one window
     * incremented and another untouched.
     *
     * <p>Returns {@code n} for an admission, or {@code -i} for a rejection,
     * where {@code i} is the 1-based position of the first exhausted window.
     * The sign keeps the two unambiguous: a rejection at the last position
     * returns {@code -n}, which can never be confused with the admission
     * value {@code n}. The caller can therefore name the exhausted window
     * without inspecting any counter value, and no counter value can leak back
     * to the application.
     */
    static final DefaultRedisScript<Long> TRY_CONSUME_ALL_SCRIPT = tryConsumeAllScript();

    private final StringRedisTemplate redis;

    private final Clock clock;

    private static DefaultRedisScript<Long> tryConsumeAllScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(String.join("\n",
                "local n = #KEYS",
                "for i = 1, n do",
                "  local current = tonumber(redis.call('GET', KEYS[i]) or '0')",
                "  local limit = tonumber(ARGV[(i - 1) * 2 + 1])",
                "  if current + 1 > limit then",
                // Rejection: nothing has been written, so no window is
                // incremented and no partial consumption can occur. The
                // position is returned negative so it can never collide with
                // the positive window count returned for an admission.
                "    return -i",
                "  end",
                "end",
                "for i = 1, n do",
                // Admission: every window had room, so increment them all.
                "  local current = tonumber(redis.call('GET', KEYS[i]) or '0')",
                "  redis.call('INCR', KEYS[i])",
                "  if current == 0 then",
                // TTL is applied only to newly created keys, so an existing
                // counter's expiry is never extended mid-window.
                "    redis.call('PEXPIRE', KEYS[i], ARGV[(i - 1) * 2 + 2])",
                "  end",
                "end",
                "return n"));
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
        return tryConsume(GatewayUsagePolicyCounterRequest.of(actorSubject, clock.instant(), window, limit))
                .isAllowed();
    }

    @Override
    public GatewayUsagePolicyCounterResult tryConsume(GatewayUsagePolicyCounterRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        List<GatewayUsagePolicyCounterWindow> windows = request.windowsInEvaluationOrder();
        List<String> keys = windows.stream().map(request::keyFor).toList();
        String[] arguments = new String[windows.size() * 2];
        for (int i = 0; i < windows.size(); i++) {
            GatewayUsagePolicyCounterWindow window = windows.get(i);
            arguments[i * 2] = String.valueOf(request.limits().get(window));
            arguments[i * 2 + 1] = String.valueOf(window.ttlMillis(request.now()));
        }

        // Exactly one script execution decides and consumes every requested
        // window. Splitting this into a read, a Java-side comparison, and
        // per-key increments would reintroduce both the check-then-act race
        // and partial consumption.
        final Long outcome;
        try {
            outcome = redis.execute(TRY_CONSUME_ALL_SCRIPT, keys, (Object[]) arguments);
        } catch (RuntimeException ex) {
            throw new GatewayUsagePolicyCounterUnavailableException(ex);
        }
        if (outcome == null) {
            // An empty result means the script did not answer. Treating that
            // as a rejection would report "limit spent" for what is really
            // "could not tell", so it fails closed as an error instead.
            throw new GatewayUsagePolicyCounterUnavailableException(
                    new IllegalStateException("Empty policy counter script result."));
        }
        if (outcome < 0L) {
            // Negative: the negative of the 1-based position of the first
            // exhausted window. The sign keeps a rejection unambiguous — a
            // positive value is always the admission window count, so a
            // rejection at the last position can never be read as an
            // admission.
            int position = (int) -outcome;
            if (position >= 1 && position <= windows.size()) {
                return GatewayUsagePolicyCounterResult.rejected(windows.get(position - 1));
            }
        } else if (outcome == (long) windows.size()) {
            // The script returns the window count only for an admission.
            return GatewayUsagePolicyCounterResult.allowed();
        }
        // Anything else is an unrecognised result and must not be read as
        // either outcome.
        throw new GatewayUsagePolicyCounterUnavailableException(
                new IllegalStateException("Unrecognised policy counter script result."));
    }
}
