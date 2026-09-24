package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.pii.PiiType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Set;

/**
 * Uniform API response for {@code POST /api/gateway/complete}. ALLOW
 * carries the provider completion; BLOCK carries the safe reason codes
 * and detected type names with no provider payload. Never request
 * content, matched values, the actor subject, or audit internals — the
 * ALLOW path exposes provider content only through the provider's own
 * response object.
 *
 * @param verdict ALLOW or BLOCK, never null
 * @param reasons safe reason codes, never null; empty exactly when ALLOW
 * @param detectedPiiTypes detected PII type names, never null
 * @param provider provider completion, present exactly when ALLOW
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayCompleteResponse(
        SecurityVerdict verdict,
        Set<BlockReason> reasons,
        Set<PiiType> detectedPiiTypes,
        LlmResponse provider) {

    public GatewayCompleteResponse {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(reasons, "reasons must not be null");
        Objects.requireNonNull(detectedPiiTypes, "detectedPiiTypes must not be null");
        reasons = Set.copyOf(reasons);
        detectedPiiTypes = Set.copyOf(detectedPiiTypes);
        if (verdict == SecurityVerdict.ALLOW && provider == null) {
            throw new IllegalArgumentException("ALLOW must carry a provider response");
        }
        if (verdict == SecurityVerdict.BLOCK && provider != null) {
            throw new IllegalArgumentException("BLOCK must not carry a provider response");
        }
    }

    /** ALLOW response carrying one provider completion. */
    public static GatewayCompleteResponse allowed(LlmResponse provider) {
        return new GatewayCompleteResponse(SecurityVerdict.ALLOW, Set.of(), Set.of(), provider);
    }

    /** BLOCK response mirroring one request-inspection result, with no provider payload. */
    public static GatewayCompleteResponse blocked(SecurityInspectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new GatewayCompleteResponse(
                SecurityVerdict.BLOCK, result.reasons(), result.detectedPiiTypes(), null);
    }

    /** BLOCK response mirroring one provider-response inspection result, with no provider payload. */
    public static GatewayCompleteResponse blocked(ProviderResponseInspectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new GatewayCompleteResponse(
                SecurityVerdict.BLOCK, result.reasons(), result.detectedPiiTypes(), null);
    }
}
