package com.aegivault.aegivault.gateway;

/**
 * Signals that one authenticated actor exceeded the gateway completion
 * rate limit. The message is the entire safe HTTP body — it carries no
 * actor, counters, timestamps, request content, or provider details.
 */
public class GatewayRateLimitExceededException extends RuntimeException {

    /** The only safe rate-limit message, shared by throw site and handler. */
    public static final String MESSAGE = "Gateway rate limit exceeded.";

    public GatewayRateLimitExceededException() {
        super(MESSAGE);
    }
}
