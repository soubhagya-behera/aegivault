package com.aegivault.aegivault.gateway.provider;

/**
 * Synchronous completion contract for one LLM provider. The gateway
 * application layer depends on this abstraction, never on a concrete
 * provider — only approved (ALLOW) requests may ever be forwarded, and
 * that forwarding is a future milestone, not part of this contract.
 *
 * <p>Implementations must be side-effect free from the gateway's point of
 * view: no logging of request content, no persistence, no audit appends,
 * no PII or secret detection, and no ALLOW/BLOCK decisions. Those
 * responsibilities stay outside the provider.
 */
public interface LlmProvider {

    /**
     * Completes one provider request.
     *
     * @param request request to complete, never null
     * @return the completion, never null
     */
    LlmResponse complete(LlmRequest request);
}
