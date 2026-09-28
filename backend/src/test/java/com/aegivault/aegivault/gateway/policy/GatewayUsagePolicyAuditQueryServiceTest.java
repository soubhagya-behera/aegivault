package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.audit.AuditLedgerService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyAuditQueryService} (no Spring
 * context, no database): actor validation and trimming, delegation to the
 * single bounded owner-scoped repository read, the closed event-type
 * vocabulary it asks for, the narrow shape of what it projects, and strict
 * read-only behavior — a history read never saves, appends, or deletes.
 */
class GatewayUsagePolicyAuditQueryServiceTest {

    private static final UUID POLICY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String ACTOR = "actor-subject";

    private final AuditLedgerEntryRepository entries = mock(AuditLedgerEntryRepository.class);

    private final GatewayUsagePolicyAuditQueryService service =
            new GatewayUsagePolicyAuditQueryService(entries);

    /**
     * A ledger row as the query would read it. Mocked rather than constructed
     * because {@code id} and {@code createdAt} are assigned on persist, which
     * a hand-built entity never sees.
     */
    private static AuditLedgerEntry entry(String eventType, String actor, UUID resourceId) {
        AuditLedgerEntry row = mock(AuditLedgerEntry.class);
        when(row.getEventType()).thenReturn(eventType);
        when(row.getActorSubject()).thenReturn(actor);
        when(row.getResourceType()).thenReturn("GATEWAY_USAGE_POLICY");
        when(row.getResourceId()).thenReturn(resourceId);
        when(row.getEventData()).thenReturn("{\"action\":\"CREATED\"}");
        when(row.getCreatedAt()).thenReturn(Instant.parse("2026-03-01T10:15:30Z"));
        when(row.getId()).thenReturn(UUID.fromString("99999999-0000-0000-0000-000000000001"));
        when(row.getSequenceNumber()).thenReturn(1L);
        when(row.getEntryHash()).thenReturn("f".repeat(64));
        when(row.getPreviousHash()).thenReturn(AuditLedgerService.GENESIS_PREVIOUS_HASH);
        return row;
    }

    /** Stubs the single repository read this service is allowed to make. */
    private void stubHistory(List<AuditLedgerEntry> rows) {
        when(entries.findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), anyList()))
                .thenReturn(rows);
    }

    @Test
    void blankActorIsRejectedBeforeAnyQueryRuns() {
        assertThatThrownBy(() -> service.historyFor(null, POLICY_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> service.historyFor("   ", POLICY_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoInteractions(entries);
    }

    @Test
    void nullPolicyIdIsRejected() {
        assertThatThrownBy(() -> service.historyFor(ACTOR, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("policyId must not be null");
        verifyNoInteractions(entries);
    }

    @Test
    void actorIsTrimmedBeforeTheRepositoryCall() {
        stubHistory(List.of());

        service.historyFor("  " + ACTOR + "  ", POLICY_ID);

        verify(entries)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        eq("GATEWAY_USAGE_POLICY"), eq(POLICY_ID), eq(ACTOR), anyList());
    }

    @Test
    void theQueryIsScopedByResourceTypeResourceIdAndActor() {
        stubHistory(List.of());

        service.historyFor(ACTOR, POLICY_ID);

        // The actor rides the same query as the resource id: there is no
        // by-resource-id-only read for a caller to reach for.
        verify(entries)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        eq(AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE),
                        eq(POLICY_ID),
                        eq(ACTOR),
                        anyList());
    }

    @Test
    void onlyTheFivePolicyEventTypesAreRequested() {
        stubHistory(List.of());

        service.historyFor(ACTOR, POLICY_ID);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> eventTypes =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(entries)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), eventTypes.capture());

        assertThat(eventTypes.getValue()).containsExactlyInAnyOrder(
                "GATEWAY_USAGE_POLICY_CREATED",
                "GATEWAY_USAGE_POLICY_UPDATED",
                "GATEWAY_USAGE_POLICY_DELETED",
                "GATEWAY_USAGE_POLICY_ALLOWED",
                "GATEWAY_USAGE_POLICY_REJECTED");
        // Inspection and unrelated events are excluded by the query itself.
        assertThat(eventTypes.getValue())
                .doesNotContain(AuditEventData.GATEWAY_INSPECTION_ALLOWED)
                .doesNotContain(AuditEventData.GATEWAY_INSPECTION_BLOCKED)
                .doesNotContain(AuditEventData.RUN_COMPLETED)
                .doesNotContain(AuditEventData.SANITIZATION_RUN_RESOURCE)
                .hasSize(5);
    }

    @Test
    void theBoundIsOneHundred() {
        assertThat(GatewayUsagePolicyAuditQueryService.MAX_ENTRIES).isEqualTo(100);
    }

    @Test
    void historyProjectsOnlyEventTypeResourceIdDataAndTimestamp() {
        AuditLedgerEntry row = entry(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED, ACTOR, POLICY_ID);
        stubHistory(List.of(row));

        var projected = service.historyFor(ACTOR, POLICY_ID);

        assertThat(projected).hasSize(1);
        GatewayUsagePolicyAuditEntry item = projected.get(0);
        assertThat(item.eventType()).isEqualTo("GATEWAY_USAGE_POLICY_UPDATED");
        assertThat(item.resourceId()).isEqualTo(POLICY_ID);
        assertThat(item.eventData()).isEqualTo(row.getEventData());
        assertThat(item.createdAt()).isNotNull();

        // Exactly these four fields: no sequence number, no chain hashes, no
        // actor subject, and no internal database identifier.
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyAuditEntry.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("eventType", "resourceId", "eventData", "createdAt");
    }

    @Test
    void projectedEntriesLeakNoChainInternalsOrActor() {
        AuditLedgerEntry row = entry(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED, ACTOR, POLICY_ID);
        stubHistory(List.of(row));

        String rendered = service.historyFor(ACTOR, POLICY_ID).get(0).toString();

        // The hash chain is evidence for the verification endpoint, not a
        // field of a per-policy history the owner reads.
        assertThat(rendered)
                .doesNotContain(AuditLedgerService.GENESIS_PREVIOUS_HASH)
                .doesNotContain(row.getEntryHash())
                .doesNotContain(row.getPreviousHash())
                .doesNotContain("sequenceNumber")
                .doesNotContain(row.getId().toString())
                .doesNotContain(ACTOR);
    }

    @Test
    void anEmptyLedgerResultBecomesAnEmptyHistory() {
        stubHistory(List.of());

        assertThat(service.historyFor(ACTOR, POLICY_ID)).isEmpty();
    }

    @Test
    void theQueryIsStrictlyReadOnly() {
        stubHistory(List.of());

        service.historyFor(ACTOR, POLICY_ID);

        // Reading history must never append, rewrite, or delete a ledger
        // entry: the ledger stays append-only. The only interaction at all
        // is the single bounded, owner-scoped read.
        verify(entries)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        eq(AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE), eq(POLICY_ID), eq(ACTOR), anyList());
        verify(entries, never()).save(any(AuditLedgerEntry.class));
        verify(entries, never()).saveAll(any());
        verify(entries, never()).saveAndFlush(any(AuditLedgerEntry.class));
        verify(entries, never()).delete(any(AuditLedgerEntry.class));
        verify(entries, never()).deleteAll();
        verify(entries, never()).deleteAllInBatch();
        // The only interaction is the single bounded read, verified above.
        verifyNoMoreInteractions(entries);
    }

    @Test
    void dependsOnlyOnTheAuditLedgerRepository() {
        var dependencies = java.util.Arrays.stream(GatewayUsagePolicyAuditQueryService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        // No policy repository or service, no enforcement service, no counter,
        // no Redis, no rate limiter, no provider: the ledger is the only way
        // this layer reaches the database.
        assertThat(dependencies).containsExactly(AuditLedgerEntryRepository.class.getName());
    }

    @Test
    void theHistoryReadIsReadOnly() throws Exception {
        var method = GatewayUsagePolicyAuditQueryService.class
                .getDeclaredMethod("historyFor", String.class, UUID.class);
        var transactional = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    void theResponseWrapperNeverCarriesANullEntryList() {
        assertThat(GatewayUsagePolicyAuditHistoryResponse.of(null).entries()).isEmpty();
        var item = new GatewayUsagePolicyAuditEntry(
                "GATEWAY_USAGE_POLICY_CREATED", POLICY_ID, "{}", Instant.now());
        var response = GatewayUsagePolicyAuditHistoryResponse.of(List.of(item));

        // Immutable: the wrapper copies, so a caller cannot mutate it later.
        assertThat(response.entries()).containsExactly(item);
        assertThat(java.util.Arrays.stream(GatewayUsagePolicyAuditHistoryResponse.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly("entries");
    }
}
