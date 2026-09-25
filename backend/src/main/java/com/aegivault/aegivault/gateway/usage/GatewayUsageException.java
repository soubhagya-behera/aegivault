package com.aegivault.aegivault.gateway.usage;

/**
 * Gateway usage persistence is unavailable: the usage row could not be
 * stored after a provider response already existed. The message is generic
 * on purpose — the cause is retained for server logs, but SQL errors,
 * database details, the actor, the request id, and exception text must
 * never reach an API response. Callers do not retry and never silently
 * claim the usage was persisted when it was not.
 */
public class GatewayUsageException extends RuntimeException {

    public GatewayUsageException(String message, Throwable cause) {
        super(message, cause);
    }
}
