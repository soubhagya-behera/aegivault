package com.aegivault.aegivault.gateway;

/**
 * Signals that the gateway rate-limit check itself could not run — for
 * example the configured Redis server is unreachable. The message is the
 * entire safe HTTP body: it carries no Redis host, exception text, keys,
 * or counters, with the cause retained in server logs only.
 *
 * <p>This is intentionally distinct from
 * {@link GatewayRateLimitExceededException} (quota spent, HTTP 429): an
 * infrastructure failure fails closed as a generic 500 instead of
 * silently bypassing rate limiting.
 */
public class GatewayRateLimitUnavailableException extends RuntimeException {

    /** The only safe unavailability message, shared by throw site and handler. */
    public static final String MESSAGE = "Unable to check gateway rate limit.";

    public GatewayRateLimitUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
