package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementResult;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementService;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.usage.GatewayUsageOutcome;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application-layer seam for gateway completions: rate-limit the actor,
 * inspect the request, then forward only what inspection allowed, then inspect the provider
 * response before returning it. One call first spends one attempt from
 * {@link GatewayRateLimiter} for the JWT-derived actor — a rejected actor
 * fails immediately as {@link GatewayRateLimitExceededException} before
 * inspection, audit, provider selection, or provider invocation — then
 * inspects the request under the
 * fixed strict policy through {@link SecurityInspectionService}, records
 * the request outcome once through {@link GatewayAuditService}, and —
 * only on request ALLOW — resolves one {@link LlmProvider} through the
 * {@link LlmProviderSelector} abstraction and forwards model plus
 * content through the selected provider. A successful provider response is
 * inspected separately through {@link ProviderResponseInspectionService}
 * under the same strict policy: a clean response returns as ALLOW with
 * the provider completion, while a sensitive response returns as BLOCK
 * with safe reason codes and no provider payload. Every provider
 * invocation that returns a response is then recorded once through
 * {@link GatewayUsageRecorder} — {@code DELIVERED} for a clean response,
 * {@code SECURITY_BLOCKED} for a blocked one (the blocked call may still
 * have consumed provider tokens) — carrying gateway metadata plus the
 * exact provider-reported counts only.
 *
 * <p>Request inspection and provider-response inspection are two
 * separate security decisions with distinct result types
 * ({@link SecurityInspectionResult} vs
 * {@link ProviderResponseInspectionResult}); the response check never
 * reuses or mutates the request decision.
 *
 * <p>Two independent admission controls run before anything else, in this
 * fixed order. First the platform-wide {@link GatewayRateLimiter} — a
 * rejected actor fails as {@link GatewayRateLimitExceededException} without
 * any policy check. Then the actor's own persistent
 * {@link GatewayUsagePolicyEnforcementService} request limits — a rejected
 * actor fails as {@link GatewayUsagePolicyLimitExceededException}, and
 * `NO_POLICY` or `INACTIVE` simply continue. They are never merged and never
 * compensate for one another: a global rejection never spends policy capacity,
 * because the policy check does not run, and the two keep distinct messages so
 * a caller can tell which control stopped it. Only after both admit does
 * inspection, audit, provider selection, or provider invocation happen, so
 * either rejection leaves no inspection audit entry and no usage record.
 *
 * <p><strong>Request limits, not token budgets.</strong> Only
 * {@code requestsPerMinute} and {@code requestsPerDay} are enforced here.
 * {@code tokensPerDay} is not: token usage is known only after a provider
 * response, whereas admission happens before provider invocation.
 *
 * <p>A provider failure — including a provider-selection failure —
 * surfaces as {@link GatewayProviderException}
 * with a generic message (cause retained for server logs): it is never
 * converted into an ALLOW/BLOCK verdict and never leaks exception text
 * or request content. An oversized provider response fails the same way
 * — never truncated, never partially inspected, never returned — before
 * response inspection runs. Response inspection runs only after a
 * successful provider response within the size bound exists — never on
 * the failure path and never on oversized output. The single
 * request-inspection audit entry already recorded is left as-is — never
 * duplicated. Provider responses are never logged or persisted.
 *
 * <p>No usage row is recorded when no provider response exists: a
 * request-side BLOCK, a rate-limit rejection, an oversized provider
 * response, and a provider (or provider-selection) failure all return or
 * fail without touching usage persistence. A usage-persistence failure
 * itself propagates as {@link com.aegivault.aegivault.gateway.usage.GatewayUsageException}
 * with a generic message instead of the ALLOW/BLOCK response.
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

    private final GatewayRateLimiter rateLimiter;

    private final GatewayUsagePolicyEnforcementService policyEnforcement;

    private final SecurityInspectionService inspections;

    private final ProviderResponseInspectionService responseInspections;

    private final LlmProviderSelector selector;

    private final GatewayAuditService audit;

    private final GatewayUsageRecorder usage;

    /**
     * Applies the actor's enabled usage policy's request limits, after the
     * global rate limiter and before anything else happens.
     *
     * <p>{@code NO_POLICY} and {@code INACTIVE} both continue: a request with
     * no policy, or with a policy that is switched off, is simply not governed
     * by one, and an inactive policy is never silently treated as a satisfied
     * one. Neither creates an audit entry, because neither is a security
     * decision.
     *
     * <p>{@code REJECTED} is <em>audited before</em> it is thrown, so the
     * evidence that the request was refused exists even though the refusal
     * never reached a provider. Failing here — before inspection — is what
     * guarantees a policy-rejected request inspects nothing, audits nothing,
     * selects no provider, invokes none, and records no usage.
     *
     * <p>Ambiguous policy configuration and an unavailable policy counter
     * propagate unchanged: both are failures to decide rather than rejections,
     * so neither can be turned into a 429 here, and neither is audited as a
     * decision because no decision was made.
     */
    private void enforceUsagePolicy(GatewayInspectionRequest inspection) {
        GatewayUsagePolicyEnforcementResult decision =
                policyEnforcement.enforce(inspection.actorSubject(), Instant.now());
        switch (decision.state()) {
            case NO_POLICY, INACTIVE -> {
                // No event: nothing was applied, so there is no decision to
                // evidence. The inspection audit below is unaffected.
            }
            case ALLOW -> audit.recordUsagePolicy(inspection.actorSubject(), decision);
            case REJECTED -> {
                // Audit first. If the append fails, the 500 propagates and the
                // request never returns 429 — a refusal whose evidence was not
                // stored must not be reported as if it had been.
                audit.recordUsagePolicy(inspection.actorSubject(), decision);
                // The rejecting window is deliberately not surfaced: the
                // response names the policy limit, never the actor's counts.
                throw new GatewayUsagePolicyLimitExceededException();
            }
            default -> throw new IllegalStateException("Unhandled policy decision state.");
        }
    }

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
        if (!rateLimiter.tryAcquire(inspection.actorSubject())) {
            throw new GatewayRateLimitExceededException();
        }
        enforceUsagePolicy(inspection);
        SecurityInspectionResult requestDecision = inspections.inspect(inspection, GatewaySecurityPolicy.strict());
        audit.record(inspection, requestDecision);
        if (requestDecision.verdict() == SecurityVerdict.BLOCK) {
            return GatewayCompleteResponse.blocked(requestDecision);
        }
        final LlmResponse completion;
        try {
            LlmProvider selected = selector.select(inspection.model());
            completion = selected.complete(new LlmRequest(inspection.model(), inspection.content()));
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
            usage.record(inspection, completion, GatewayUsageOutcome.SECURITY_BLOCKED);
            return GatewayCompleteResponse.blocked(responseDecision);
        }
        usage.record(inspection, completion, GatewayUsageOutcome.DELIVERED);
        return GatewayCompleteResponse.allowed(completion);
    }
}
