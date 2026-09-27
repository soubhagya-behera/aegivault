package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerException;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterWindow;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementResult;
import com.aegivault.aegivault.pii.PiiType;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application-layer seam between gateway inspection and the audit ledger:
 * records one ledger entry per successfully inspected request, carrying
 * safe metadata only. The inspection itself stays in
 * {@link SecurityInspectionService} (deterministic, side-effect free);
 * this service only translates its outcome into one append.
 *
 * <p>The entry reflects the final verdict: {@code ALLOW} inspections use
 * {@link AuditEventData#GATEWAY_INSPECTION_ALLOWED}, {@code BLOCK}
 * inspections use {@link AuditEventData#GATEWAY_INSPECTION_BLOCKED}. The
 * actor is the JWT subject, the resource id is the server-generated
 * request id, and the event data holds model, verdict, reason codes, and
 * detected type names — never request content, matched values, or
 * secrets. An append failure surfaces as {@link AuditLedgerException},
 * exactly like the sanitization run path, and is never swallowed.
 *
 * <p>Usage policy decisions are recorded separately by
 * {@link #recordUsagePolicy(String, GatewayUsagePolicyEnforcementResult)}
 * under their own event types and resource type, because a quota decision and
 * a security inspection are different decisions about the same request.
 */
@Service
@RequiredArgsConstructor
public class GatewayAuditService {

    private final AuditLedgerService audit;

    /**
     * Records one gateway usage policy decision. Exactly one entry per applied
     * decision, written before the request continues or is refused.
     *
     * <p><strong>Only an applied policy is recorded.</strong> {@code NO_POLICY}
     * appends nothing, because no policy was consulted and there is no decision
     * to evidence. {@code INACTIVE} also appends nothing: a disabled policy is
     * not applied, and writing an {@code ALLOWED} event for it would claim a
     * quota was checked and satisfied when in fact nothing ran. That keeps the
     * ledger's word "allowed" meaning exactly "an enabled policy admitted this".
     *
     * <p>The event data is closed-vocabulary only: the decision code, the
     * windows that were actually checked, or the window that refused. Never the
     * actor (the ledger already stores it), the configured limits, the current
     * usage counts, counter values, Redis keys, policy owner or label, request
     * content, provider output, PII, or secrets.
     *
     * @param actorSubject the JWT subject, never null; stored by the ledger
     *        itself and never repeated into the event data
     * @param decision the policy decision to record, never null
     * @throws GatewayUsagePolicyAuditException when the append fails
     */
    public void recordUsagePolicy(
            String actorSubject, GatewayUsagePolicyEnforcementResult decision) {
        Objects.requireNonNull(actorSubject, "actorSubject must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        String eventType;
        String eventData;
        switch (decision.state()) {
            case ALLOW -> {
                eventType = AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED;
                eventData = AuditEventData.gatewayUsagePolicyAllowed(
                        decision.state().name(), windowNames(decision.enforcedWindows()));
            }
            case REJECTED -> {
                eventType = AuditEventData.GATEWAY_USAGE_POLICY_REJECTED;
                eventData = AuditEventData.gatewayUsagePolicyRejected(
                        decision.state().name(), decision.rejectedWindow().name());
            }
            // No event: nothing was applied, so there is no decision to record.
            case NO_POLICY, INACTIVE -> {
                return;
            }
            default -> throw new IllegalStateException("Unhandled policy decision state.");
        }

        try {
            audit.append(
                    eventType,
                    actorSubject,
                    AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE,
                    decision.policyId(),
                    eventData);
        } catch (RuntimeException ex) {
            throw new GatewayUsagePolicyAuditException(ex);
        }
    }

    /**
     * Window names in the decision's own fixed evaluation order, so the
     * serialized bytes never shift between runs.
     */
    private static List<String> windowNames(List<GatewayUsagePolicyCounterWindow> windows) {
        return windows.stream().map(Enum::name).toList();
    }

    /**
     * Records one inspected request. Exactly one entry per call; callers
     * invoke this once per inspection, after the verdict is known.
     */
    public void record(GatewayInspectionRequest request, SecurityInspectionResult result) {
        String eventType = result.verdict() == SecurityVerdict.ALLOW
                ? AuditEventData.GATEWAY_INSPECTION_ALLOWED
                : AuditEventData.GATEWAY_INSPECTION_BLOCKED;
        List<String> reasons = result.reasons().stream().map(BlockReason::name).toList();
        List<String> detected = result.detectedPiiTypes().stream().map(PiiType::name).toList();
        String eventData =
                AuditEventData.gatewayInspection(request.model(), result.verdict().name(), reasons, detected);
        try {
            audit.append(
                    eventType,
                    request.actorSubject(),
                    AuditEventData.AI_GATEWAY_INSPECTION_RESOURCE,
                    request.requestId(),
                    eventData);
        } catch (RuntimeException ex) {
            throw new AuditLedgerException("Unable to record audit event.", ex);
        }
    }
}
