package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application-layer seam for gateway completions: inspect the request,
 * then forward only what inspection allowed, then inspect the provider
 * response before returning it. One call inspects the request under the
 * fixed strict policy through {@link SecurityInspectionService}, records
 * the request outcome once through {@link GatewayAuditService}, and —
 * only on request ALLOW — forwards model plus content through the
 * {@link LlmProvider} abstraction. A successful provider response is
 * inspected separately through {@link ProviderResponseInspectionService}
 * under the same strict policy: a clean response returns as ALLOW with
 * the provider completion, while a sensitive response returns as BLOCK
 * with safe reason codes and no provider payload.
 *
 * <p>Request inspection and provider-response inspection are two
 * separate security decisions with distinct result types
 * ({@link SecurityInspectionResult} vs
 * {@link ProviderResponseInspectionResult}); the response check never
 * reuses or mutates the request decision.
 *
 * <p>A provider failure surfaces as {@link GatewayProviderException}
 * with a generic message (cause retained for server logs): it is never
 * converted into an ALLOW/BLOCK verdict and never leaks exception text
 * or request content. An oversized provider response fails the same way
 * — never truncated, never partially inspected, never returned — before
 * response inspection runs. Response inspection runs only after a
 * successful provider response within the size bound exists — never on
 * the failure path and never on oversized output. The single
 * request-inspection audit entry already recorded is left as-is — never
 * duplicated. Provider responses are never logged or persisted.
 */
@Service
@RequiredArgsConstructor
public class GatewayCompletionService {

    /**
     * Maximum provider-response content in characters (64 KiB). The single
     * provider-output size bound: reuses the gateway input-size bound
     * ({@link GatewayInspectRequest#MAX_CONTENT_LENGTH}) rather than
     * redefining the number, so request and response share one boundary
     * concept. Enforced after the provider call and before response
     * inspection — oversized output fails safely instead of being
     * inspected, truncated, or returned.
     */
    public static final int MAX_PROVIDER_RESPONSE_LENGTH = GatewayInspectRequest.MAX_CONTENT_LENGTH;

    private final SecurityInspectionService inspections;

    private final ProviderResponseInspectionService responseInspections;

    private final LlmProvider providers;

    private final GatewayAuditService audit;

    /**
     * Inspects one request and completes it when allowed.
     *
     * @param inspection inspection input with server-generated id and
     *        JWT-derived actor, never null
     * @return ALLOW with the clean provider completion, or BLOCK with safe
     *         reason codes and no provider payload (either the request was
     *         blocked before reaching the provider, or the provider
     *         response was blocked instead of being returned)
     */
    public GatewayCompleteResponse complete(GatewayInspectionRequest inspection) {
        SecurityInspectionResult requestDecision = inspections.inspect(inspection, GatewaySecurityPolicy.strict());
        audit.record(inspection, requestDecision);
        if (requestDecision.verdict() == SecurityVerdict.BLOCK) {
            return GatewayCompleteResponse.blocked(requestDecision);
        }
        final LlmResponse completion;
        try {
            completion = providers.complete(new LlmRequest(inspection.model(), inspection.content()));
        } catch (RuntimeException ex) {
            throw new GatewayProviderException("Unable to complete gateway request.", ex);
        }
        if (completion.content().length() > MAX_PROVIDER_RESPONSE_LENGTH) {
            throw new GatewayProviderException(
                    "Unable to complete gateway request.",
                    new IllegalStateException("Provider response exceeded maximum length."));
        }
        ProviderResponseInspectionResult responseDecision =
                responseInspections.inspect(completion, GatewaySecurityPolicy.strict());
        if (responseDecision.verdict() == SecurityVerdict.BLOCK) {
            return GatewayCompleteResponse.blocked(responseDecision);
        }
        return GatewayCompleteResponse.allowed(completion);
    }
}
