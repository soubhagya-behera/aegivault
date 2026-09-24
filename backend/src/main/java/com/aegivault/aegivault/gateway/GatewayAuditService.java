package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerException;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.pii.PiiType;
import java.util.List;
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
 */
@Service
@RequiredArgsConstructor
public class GatewayAuditService {

    private final AuditLedgerService audit;

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
