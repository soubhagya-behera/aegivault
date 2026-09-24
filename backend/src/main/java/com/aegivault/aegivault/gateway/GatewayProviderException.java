package com.aegivault.aegivault.gateway;

/**
 * A provider completion that should exist does not: the
 * {@code LlmProvider} call failed after inspection and its audit entry.
 * The message is generic on purpose — the cause is retained for server
 * logs, but provider internals, exception text, and request content must
 * never reach an API response. Callers do not retry and never convert
 * the failure into an ALLOW/BLOCK verdict.
 */
public class GatewayProviderException extends RuntimeException {

    public GatewayProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
