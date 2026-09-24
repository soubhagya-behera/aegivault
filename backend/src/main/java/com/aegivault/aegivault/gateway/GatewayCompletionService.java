package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application-layer seam for gateway completions: inspect, then forward
 * only what inspection allowed. One call inspects under the fixed strict
 * policy through {@link SecurityInspectionService}, records the outcome
 * once through {@link GatewayAuditService}, and — only on ALLOW —
 * forwards model plus content through the {@link LlmProvider}
 * abstraction. BLOCK never reaches the provider.
 *
 * <p>A provider failure surfaces as {@link GatewayProviderException}
 * with a generic message (cause retained for server logs): it is never
 * converted into an ALLOW/BLOCK verdict and never leaks exception text
 * or request content. The single inspection audit entry already recorded
 * is left as-is — never duplicated.
 */
@Service
@RequiredArgsConstructor
public class GatewayCompletionService {

    private final SecurityInspectionService inspections;

    private final LlmProvider providers;

    private final GatewayAuditService audit;

    /**
     * Inspects one request and completes it when allowed.
     *
     * @param inspection inspection input with server-generated id and
     *        JWT-derived actor, never null
     * @return ALLOW with the provider completion, or BLOCK with safe
     *         reason codes and no provider payload
     */
    public GatewayCompleteResponse complete(GatewayInspectionRequest inspection) {
        SecurityInspectionResult result = inspections.inspect(inspection, GatewaySecurityPolicy.strict());
        audit.record(inspection, result);
        if (result.verdict() == SecurityVerdict.BLOCK) {
            return GatewayCompleteResponse.blocked(result);
        }
        try {
            LlmResponse completion =
                    providers.complete(new LlmRequest(inspection.model(), inspection.content()));
            return GatewayCompleteResponse.allowed(completion);
        } catch (RuntimeException ex) {
            throw new GatewayProviderException("Unable to complete gateway request.", ex);
        }
    }
}
