package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounterWindow;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for policy decision auditing (no Spring context, no
 * database, no HTTP, no Redis). They pin the event types, the exact metadata
 * bytes, the states that record nothing, and the absence of anything
 * sensitive — including that the actor is never repeated into the event data
 * even though the ledger already stores it in its own column.
 */
class GatewayPolicyAuditTest {

    private static final UUID POLICY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String ACTOR = "actor-secret-subject";

    private AuditLedgerService ledger;

    private GatewayAuditService auditService;

    @BeforeEach
    void setUp() {
        ledger = mock(AuditLedgerService.class);
        auditService = new GatewayAuditService(ledger);
    }

    private static GatewayUsagePolicyEnforcementResult allow(
            List<GatewayUsagePolicyCounterWindow> windows) {
        return GatewayUsagePolicyEnforcementResult.allow(POLICY_ID, windows);
    }

    /** Captures the single appended event data string. */
    private String appendedEventData() {
        ArgumentCaptor<String> eventData = ArgumentCaptor.forClass(String.class);
        verify(ledger).append(anyString(), anyString(), any(), any(), eventData.capture());
        return eventData.getValue();
    }

    @Test
    void anAllowedDecisionWritesThePolicyAllowedEvent() {
        auditService.recordUsagePolicy(ACTOR, allow(
                List.of(GatewayUsagePolicyCounterWindow.DAY, GatewayUsagePolicyCounterWindow.MINUTE)));

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resourceId = ArgumentCaptor.forClass(UUID.class);
        verify(ledger).append(
                eventType.capture(), anyString(), resourceType.capture(), resourceId.capture(), any());
        assertThat(eventType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY_ALLOWED");
        assertThat(eventType.getValue()).isNotEqualTo(AuditEventData.GATEWAY_INSPECTION_ALLOWED);
        assertThat(resourceType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY");
        assertThat(resourceId.getValue()).isEqualTo(POLICY_ID);
    }

    @Test
    void allowedMetadataNamesTheDecisionAndTheEnforcedWindows() {
        auditService.recordUsagePolicy(ACTOR, allow(
                List.of(GatewayUsagePolicyCounterWindow.DAY, GatewayUsagePolicyCounterWindow.MINUTE)));

        assertThat(appendedEventData())
                .isEqualTo("{\"decision\":\"ALLOW\",\"enforcedWindows\":[\"DAY\",\"MINUTE\"]}");
    }

    @Test
    void theEventDataIsByteStableAcrossRepeatedIdenticalDecisions() {
        // Determinism matters because these bytes are hashed into the ledger
        // chain: the same decision must always produce the same text.
        assertThat(AuditEventData.gatewayUsagePolicyRejected("REJECTED", "MINUTE"))
                .isEqualTo(AuditEventData.gatewayUsagePolicyRejected("REJECTED", "MINUTE"));
        assertThat(AuditEventData.gatewayUsagePolicyAllowed("ALLOW", List.of("DAY", "MINUTE")))
                .isEqualTo(AuditEventData.gatewayUsagePolicyAllowed("ALLOW", List.of("DAY", "MINUTE")));
    }

    @Test
    void aRejectedDecisionWritesThePolicyRejectedEventNamingTheWindow() {
        auditService.recordUsagePolicy(ACTOR, GatewayUsagePolicyEnforcementResult.rejected(
                GatewayUsagePolicyCounterWindow.MINUTE,
                POLICY_ID,
                List.of(GatewayUsagePolicyCounterWindow.DAY, GatewayUsagePolicyCounterWindow.MINUTE)));

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        verify(ledger).append(eventType.capture(), anyString(), any(), any(), any());
        assertThat(eventType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY_REJECTED");
        assertThat(eventType.getValue()).isNotEqualTo(AuditEventData.GATEWAY_INSPECTION_BLOCKED);
        assertThat(appendedEventData())
                .isEqualTo("{\"decision\":\"REJECTED\",\"rejectedWindow\":\"MINUTE\"}");
    }

    @Test
    void noPolicyWritesNoEventAtAll() {
        // Nothing was consulted, so there is no decision to evidence.
        auditService.recordUsagePolicy(ACTOR, GatewayUsagePolicyEnforcementResult.noPolicy());

        verifyNoInteractions(ledger);
    }

    @Test
    void anInactivePolicyWritesNoEventAtAll() {
        // Documented choice: a disabled policy is not applied, so an ALLOWED
        // event would claim a quota was checked when nothing ran. "ALLOWED" in
        // the ledger therefore always means "an enabled policy admitted this".
        auditService.recordUsagePolicy(ACTOR, GatewayUsagePolicyEnforcementResult.inactive(POLICY_ID));

        verifyNoInteractions(ledger);
    }

    @Test
    void policyMetadataNeverContainsTheActor() {
        // The ledger already stores the actor as its own column; repeating it
        // in the event data would duplicate an identifier for no reason.
        auditService.recordUsagePolicy(ACTOR, allow(
                List.of(GatewayUsagePolicyCounterWindow.DAY, GatewayUsagePolicyCounterWindow.MINUTE)));

        assertThat(appendedEventData()).doesNotContain(ACTOR);
    }

    @Test
    void policyMetadataCarriesNoCountsLimitsKeysOrContent() {
        auditService.recordUsagePolicy(ACTOR, GatewayUsagePolicyEnforcementResult.rejected(
                GatewayUsagePolicyCounterWindow.DAY,
                POLICY_ID,
                List.of(GatewayUsagePolicyCounterWindow.DAY)));

        String data = appendedEventData();

        // Closed-vocabulary facts only: no usage count, configured limit,
        // counter value, Redis key, content, PII, or secret.
        assertThat(data).isEqualTo("{\"decision\":\"REJECTED\",\"rejectedWindow\":\"DAY\"}");
        assertThat(data)
                .doesNotContain("aegivault:gateway:policy-counter")
                .doesNotContain("localhost")
                .doesNotContain("6379")
                .doesNotContain("actor")
                .doesNotContain("count")
                .doesNotContain("limit")
                .doesNotContain("prompt")
                .doesNotContain("content")
                .doesNotContain("sk-")
                .doesNotContain("@example.com");
    }

    @Test
    void anAppendFailureBecomesTheSafePolicyAuditException() {
        when(ledger.append(anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("psql-constraint-violation-7q"));

        assertThatThrownBy(() -> auditService.recordUsagePolicy(ACTOR, allow(
                        List.of(GatewayUsagePolicyCounterWindow.MINUTE))))
                .isInstanceOf(GatewayUsagePolicyAuditException.class)
                .hasMessage(GatewayUsagePolicyAuditException.MESSAGE)
                .hasMessage("Unable to record gateway policy audit event.")
                // The cause is kept for logs but never surfaces in the body.
                .hasMessageNotContaining("psql-constraint-violation");
    }

    @Test
    void thePolicyEventTypesAreDistinctFromTheInspectionEventTypes() {
        // Two different decisions about one request must never share a name.
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED)
                .isNotEqualTo(AuditEventData.GATEWAY_INSPECTION_ALLOWED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED);
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED)
                .isNotEqualTo(AuditEventData.GATEWAY_INSPECTION_BLOCKED);
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE)
                .isNotEqualTo(AuditEventData.AI_GATEWAY_INSPECTION_RESOURCE);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> auditService.recordUsagePolicy(null, allow(List.of())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> auditService.recordUsagePolicy(ACTOR, null))
                .isInstanceOf(NullPointerException.class);
    }
}
