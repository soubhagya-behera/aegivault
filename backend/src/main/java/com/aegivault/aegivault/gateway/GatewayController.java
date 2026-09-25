package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.audit.AuditLedgerException;
import com.aegivault.aegivault.gateway.usage.GatewayUsageException;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated gateway endpoints. The owner always comes from the
 * verified JWT subject; the client can never supply or override it.
 * This controller is thin by design: it binds the minimal payload,
 * generates the request id server-side, and delegates — inspection to
 * {@link SecurityInspectionService} plus {@link GatewayAuditService},
 * completions to {@link GatewayCompletionService}. A {@code BLOCK} verdict
 * is a successful inspection, so it is returned as data with HTTP 200,
 * never as an error status.
 *
 * <p>{@code POST /api/gateway/inspect} is inspection-only: it never calls
 * a provider. {@code POST /api/gateway/complete} inspects first under the
 * fixed strict policy and forwards only ALLOW requests to the configured
 * {@code LlmProvider}; BLOCK never reaches the provider.
 *
 * <p>One successfully inspected request produces exactly one audit ledger
 * entry carrying safe metadata only (model, verdict, reason codes,
 * detected type names). Requests that never reach inspection —
 * unauthenticated, malformed, invalid, or oversized — produce no entry.
 * There are still no external calls and no logging of request content.
 * The configured provider is the local deterministic mock — no external
 * provider forwarding exists yet.
 */
@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final SecurityInspectionService inspections;

    private final GatewayAuditService audit;

    private final GatewayCompletionService completions;

    /**
     * Inspects one AI request and returns ALLOW or BLOCK with safe reason
     * codes and detected PII type names. Never returns request content,
     * matched values, or the actor subject. Never calls a provider.
     */
    @PostMapping("/inspect")
    public SecurityInspectionResult inspect(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GatewayInspectRequest request) {
        GatewayInspectionRequest inspection = new GatewayInspectionRequest(
                UUID.randomUUID(), jwt.getSubject(), request.model(), request.content());
        SecurityInspectionResult result = inspections.inspect(inspection, GatewaySecurityPolicy.strict());
        audit.record(inspection, result);
        return result;
    }

    /**
     * Inspects one AI request and, only when inspection allows it,
     * completes it through the configured provider. BLOCK returns the
     * safe decision with no provider payload and never invokes the
     * provider. Never returns request content, matched values, the actor
     * subject, or audit internals.
     *
     * <p>The actor's rate-limit attempt is spent first inside
     * {@link GatewayCompletionService}: a rate-limited request fails as
     * HTTP 429 before inspection, audit, provider selection, or provider
     * invocation, so it appends no inspection audit entry.
     */
    @PostMapping("/complete")
    public GatewayCompleteResponse complete(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GatewayCompleteRequest request) {
        GatewayInspectionRequest inspection = new GatewayInspectionRequest(
                UUID.randomUUID(), jwt.getSubject(), request.model(), request.content());
        return completions.complete(inspection);
    }

    /**
     * Audit infrastructure failure: the inspection it records already ran,
     * so success is not claimed — generic 500 with no storage details,
     * cause retained in server logs only. Mirrors the run endpoint's
     * {@code AuditLedgerException} handling.
     */
    @ExceptionHandler(AuditLedgerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    GatewayError auditFailed(AuditLedgerException ex) {
        return new GatewayError("Unable to record audit event.");
    }

    /**
     * Rate-limit rejection: the actor spent its completion quota, so
     * nothing was inspected, audited, or forwarded — generic 429 with
     * the safe message only, never counters, timestamps, the actor
     * subject, or request content. Distinct from a security BLOCK,
     * which stays HTTP 200 data.
     */
    @ExceptionHandler(GatewayRateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    GatewayError rateLimitExceeded(GatewayRateLimitExceededException ex) {
        return new GatewayError(GatewayRateLimitExceededException.MESSAGE);
    }

    /**
     * Rate-limit infrastructure failure: the quota check itself could not
     * run, so success is not claimed and the request is not let through —
     * generic 500 with no Redis details, cause retained in server logs
     * only. Distinct from quota rejection (HTTP 429) above.
     */
    @ExceptionHandler(GatewayRateLimitUnavailableException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    GatewayError rateLimitUnavailable(GatewayRateLimitUnavailableException ex) {
        return new GatewayError(GatewayRateLimitUnavailableException.MESSAGE);
    }

    /**
     * Provider failure: inspection and its audit entry already happened,
     * so success is not claimed — generic 500 with no provider details,
     * exception text, or request content, cause retained in server logs
     * only.
     */
    @ExceptionHandler(GatewayProviderException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    GatewayError providerFailed(GatewayProviderException ex) {
        return new GatewayError("Unable to complete gateway request.");
    }

    /**
     * Usage persistence failure: a provider response already existed, but
     * its usage row could not be stored, so success is not claimed —
     * generic 500 with no SQL details, actor, request id, or exception
     * text, cause retained in server logs only. Distinct from the provider
     * failure above, whose message stays unchanged.
     */
    @ExceptionHandler(GatewayUsageException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    GatewayError usageFailed(GatewayUsageException ex) {
        return new GatewayError("Unable to record gateway usage.");
    }
}
