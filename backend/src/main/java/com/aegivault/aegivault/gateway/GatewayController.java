package com.aegivault.aegivault.gateway;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated gateway inspection endpoint. The owner always comes from
 * the verified JWT subject; the client can never supply or override it.
 * This controller is thin by design: it binds the minimal payload,
 * generates the request id server-side, inspects under the fixed strict
 * policy through {@link SecurityInspectionService}, and returns the safe
 * decision record. A {@code BLOCK} verdict is a successful inspection, so
 * it is returned as data with HTTP 200, never as an error status.
 *
 * <p>Strictly side-effect free: no external or LLM calls, no database
 * writes, no audit entries, no logging of request content. In particular
 * this endpoint forwards nothing to any AI provider — provider integration
 * remains a later milestone.
 */
@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final SecurityInspectionService inspections;

    /**
     * Inspects one AI request and returns ALLOW or BLOCK with safe reason
     * codes and detected PII type names. Never returns request content,
     * matched values, or the actor subject.
     */
    @PostMapping("/inspect")
    public SecurityInspectionResult inspect(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GatewayInspectRequest request) {
        GatewayInspectionRequest inspection = new GatewayInspectionRequest(
                UUID.randomUUID(), jwt.getSubject(), request.model(), request.content());
        return inspections.inspect(inspection, GatewaySecurityPolicy.strict());
    }
}
