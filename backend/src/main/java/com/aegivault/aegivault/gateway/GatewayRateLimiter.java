package com.aegivault.aegivault.gateway;

/**
 * Decides whether one authenticated gateway actor may start a completion.
 * The key is always the verified JWT subject — never an IP address, a
 * model name, or request content. This abstraction is deliberately free
 * of HTTP, Spring MVC, Redis, database, provider, and PII-detector
 * concepts so a later distributed implementation can replace the local
 * one without touching callers.
 */
@FunctionalInterface
public interface GatewayRateLimiter {

    /**
     * Records one attempt for the given actor and reports whether it may
     * proceed. Allowed attempts consume quota; rejected attempts must not
     * trigger inspection, audit, provider selection, or provider calls —
     * that ordering is the caller's responsibility.
     *
     * @param actorSubject verified JWT subject, never blank
     * @return {@code true} when the actor may proceed, {@code false} when
     *         the actor is currently rate-limited
     */
    boolean tryAcquire(String actorSubject);
}
