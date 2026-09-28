package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for gateway usage policy <em>lifecycle</em> auditing (no
 * Spring context, no database, no HTTP, no Redis). They pin the three
 * lifecycle event types, the exact metadata bytes, the resource fields, and
 * the absence of anything sensitive — including that the actor is never
 * repeated into the event data even though the ledger already stores it in
 * its own column.
 *
 * <p>These cover the audit seam only. Whether the seam is reached at all, and
 * only after a mutation actually succeeded, is proven by
 * {@code GatewayUsagePolicyLifecycleAuditApiTest} and by the end-to-end
 * assertions in {@code GatewayUsagePolicyApiTest}.
 */
class GatewayPolicyLifecycleAuditTest {

    private static final UUID POLICY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String ACTOR = "actor-secret-subject";

    private AuditLedgerService ledger;

    private GatewayAuditService auditService;

    @BeforeEach
    void setUp() {
        ledger = mock(AuditLedgerService.class);
        auditService = new GatewayAuditService(ledger);
    }

    /** Captures the single appended event data string. */
    private String appendedEventData() {
        ArgumentCaptor<String> eventData = ArgumentCaptor.forClass(String.class);
        verify(ledger).append(anyString(), anyString(), any(), any(), eventData.capture());
        return eventData.getValue();
    }

    @Test
    void aCreateWritesThePolicyCreatedEvent() {
        auditService.recordPolicyCreated(ACTOR, POLICY_ID);

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resourceId = ArgumentCaptor.forClass(UUID.class);
        verify(ledger).append(
                eventType.capture(), anyString(), resourceType.capture(), resourceId.capture(), any());
        assertThat(eventType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY_CREATED");
        assertThat(resourceType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY");
        assertThat(resourceId.getValue()).isEqualTo(POLICY_ID);
    }

    @Test
    void anUpdateWritesThePolicyUpdatedEvent() {
        auditService.recordPolicyUpdated(ACTOR, POLICY_ID);

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resourceId = ArgumentCaptor.forClass(UUID.class);
        verify(ledger).append(eventType.capture(), anyString(), any(), resourceId.capture(), any());
        assertThat(eventType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY_UPDATED");
        assertThat(resourceId.getValue()).isEqualTo(POLICY_ID);
    }

    @Test
    void aDeleteWritesThePolicyDeletedEvent() {
        auditService.recordPolicyDeleted(ACTOR, POLICY_ID);

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resourceId = ArgumentCaptor.forClass(UUID.class);
        verify(ledger).append(eventType.capture(), anyString(), any(), resourceId.capture(), any());
        assertThat(eventType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY_DELETED");
        assertThat(resourceId.getValue()).isEqualTo(POLICY_ID);
    }

    @Test
    void theLifecycleMetadataIsExactlyTheActionCode() {
        auditService.recordPolicyCreated(ACTOR, POLICY_ID);
        assertThat(appendedEventData()).isEqualTo("{\"action\":\"CREATED\"}");

        ledger = mock(AuditLedgerService.class);
        auditService = new GatewayAuditService(ledger);
        auditService.recordPolicyUpdated(ACTOR, POLICY_ID);
        assertThat(appendedEventData()).isEqualTo("{\"action\":\"UPDATED\"}");

        ledger = mock(AuditLedgerService.class);
        auditService = new GatewayAuditService(ledger);
        auditService.recordPolicyDeleted(ACTOR, POLICY_ID);
        assertThat(appendedEventData()).isEqualTo("{\"action\":\"DELETED\"}");
    }

    @Test
    void theActorIsStoredOnlyInTheStandardAuditColumn() {
        auditService.recordPolicyCreated(ACTOR, POLICY_ID);

        // The ledger has a dedicated actor column; repeating the subject in
        // the metadata would duplicate an identifier for no audit value.
        ArgumentCaptor<String> actorSubject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventData = ArgumentCaptor.forClass(String.class);
        verify(ledger).append(anyString(), actorSubject.capture(), any(), any(), eventData.capture());
        assertThat(actorSubject.getValue()).isEqualTo(ACTOR);
        assertThat(eventData.getValue()).doesNotContain(ACTOR).doesNotContain("actor");
    }

    @Test
    void lifecycleMetadataCopiesNoPolicyData() {
        auditService.recordPolicyLifecycle(
                AuditEventData.GATEWAY_USAGE_POLICY_UPDATED, "UPDATED", ACTOR, POLICY_ID);

        // One closed-vocabulary field. The ledger proves the policy changed;
        // it does not become a second copy of the policy.
        String data = appendedEventData();
        assertThat(data).isEqualTo("{\"action\":\"UPDATED\"}");
        assertThat(data)
                .doesNotContain("name")
                .doesNotContain("description")
                .doesNotContain("requestsPerMinute")
                .doesNotContain("requestsPerDay")
                .doesNotContain("tokensPerDay")
                .doesNotContain("enabled")
                .doesNotContain("ownerSubject")
                .doesNotContain("aegivault:gateway:policy-counter")
                .doesNotContain("localhost")
                .doesNotContain("6379")
                .doesNotContain("prompt")
                .doesNotContain("content")
                .doesNotContain("sk-")
                .doesNotContain("@example.com");
    }

    @Test
    void theLifecycleEventTypesAreDistinctFromTheEnforcementAndInspectionTypes() {
        // A definition change and a quota decision are different facts about
        // the same resource, and neither is a security verdict.
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_CREATED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_DELETED)
                .isNotEqualTo(AuditEventData.GATEWAY_INSPECTION_ALLOWED)
                .isNotEqualTo(AuditEventData.GATEWAY_INSPECTION_BLOCKED);
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED);
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_DELETED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED)
                .isNotEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED);
        // One resource type for every event about a policy, so a reader can
        // follow a single policy's whole history by id.
        assertThat(AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE).isEqualTo("GATEWAY_USAGE_POLICY");
    }

    @Test
    void anAppendFailureBecomesTheSafePolicyAuditException() {
        when(ledger.append(anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("psql-constraint-violation-7q"));

        assertThatThrownBy(() -> auditService.recordPolicyDeleted(ACTOR, POLICY_ID))
                .isInstanceOf(GatewayUsagePolicyAuditException.class)
                .hasMessage(GatewayUsagePolicyAuditException.MESSAGE)
                .hasMessage("Unable to record gateway policy audit event.")
                // The cause is kept for logs but never surfaces in the body.
                .hasMessageNotContaining("psql-constraint-violation");
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> auditService.recordPolicyCreated(null, POLICY_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> auditService.recordPolicyCreated(ACTOR, null))
                .isInstanceOf(NullPointerException.class);
    }
}
