package com.aegivault.aegivault.gateway.provider;

/**
 * An Ollama completion that should exist does not: the local Ollama
 * HTTP call failed (connection failure, timeout, non-2xx status, or
 * malformed response). The message is generic on purpose — host/port
 * internals, the raw Ollama response body, and request content must
 * never reach an API response; the cause is retained for server logs
 * only. The gateway completion service wraps this into its existing
 * generic {@code GatewayProviderException} like any other provider
 * failure.
 */
public class OllamaProviderException extends RuntimeException {

    public OllamaProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
