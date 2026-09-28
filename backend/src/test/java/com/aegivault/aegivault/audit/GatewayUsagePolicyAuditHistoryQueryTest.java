package com.aegivault.aegivault.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/**
 * The owner-scoped policy history query against real PostgreSQL, using the
 * same {@code @DataJpaTest} slice as the ledger's own tests, so no new
 * application context and no extra pooled datasource is introduced.
 *
 * <p>Rows are written through the real {@link AuditLedgerService}, so the
 * chain, hashes, and timestamps are genuine rather than fabricated. Every
 * test uses its own random actor and resource id, so it never collides with
 * rows other suites commit into the shared, global ledger table — the
 * surrounding test transaction rolls everything back.
 *
 * <p>What is pinned here is the contract the API depends on: owner and
 * resource isolation, the closed event-type vocabulary, newest-first order
 * that stays deterministic when timestamps tie, history that outlives the
 * policy row, and the database-side 100-entry bound.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class GatewayUsagePolicyAuditHistoryQueryTest {

    private static final String RESOURCE_TYPE = "GATEWAY_USAGE_POLICY";

    private static final List<String> POLICY_EVENTS = List.of(
            "GATEWAY_USAGE_POLICY_CREATED",
            "GATEWAY_USAGE_POLICY_UPDATED",
            "GATEWAY_USAGE_POLICY_DELETED",
            "GATEWAY_USAGE_POLICY_ALLOWED",
            "GATEWAY_USAGE_POLICY_REJECTED");

    @Autowired
    private AuditLedgerEntryRepository entries;

    @PersistenceContext
    private EntityManager entities;

    private AuditLedgerService ledger;

    @BeforeEach
    void service() {
        ledger = new AuditLedgerService(entries);
    }

    /** A per-test actor so this suite never sees another suite's rows. */
    private static String actor() {
        return "history-query-actor-" + UUID.randomUUID();
    }

    private void append(String eventType, String actor, UUID resourceId, String data) {
        ledger.append(eventType, actor, RESOURCE_TYPE, resourceId, data);
    }

    private List<AuditLedgerEntry> history(String actor, UUID resourceId) {
        return entries.findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                RESOURCE_TYPE, resourceId, actor, POLICY_EVENTS);
    }

    private static void pause() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void returnsTheFivePolicyEventTypesForThatActorAndResource() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");
        pause();
        append("GATEWAY_USAGE_POLICY_UPDATED", actor, policy, "{\"action\":\"UPDATED\"}");
        pause();
        append("GATEWAY_USAGE_POLICY_DELETED", actor, policy, "{\"action\":\"DELETED\"}");
        append("GATEWAY_USAGE_POLICY_ALLOWED", actor, policy, "{\"decision\":\"ALLOW\",\"enforcedWindows\":[]}");
        append("GATEWAY_USAGE_POLICY_REJECTED", actor, policy, "{\"decision\":\"REJECTED\",\"rejectedWindow\":\"MINUTE\"}");

        List<AuditLedgerEntry> found = history(actor, policy);

        assertThat(found).hasSize(5);
        assertThat(found).extracting(AuditLedgerEntry::getEventType)
                .containsExactlyInAnyOrderElementsOf(POLICY_EVENTS);
        // The stored metadata comes back verbatim, never re-derived.
        assertThat(found).extracting(AuditLedgerEntry::getEventData)
                .contains("{\"action\":\"CREATED\"}", "{\"action\":\"UPDATED\"}", "{\"action\":\"DELETED\"}");
    }

    @Test
    void excludesUnrelatedAndInspectionEventTypes() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");
        // Same actor and same resource, but not policy events: these must not
        // appear in a policy history.
        ledger.append("AI_GATEWAY_INSPECTION_ALLOWED", actor, RESOURCE_TYPE, policy, "{\"verdict\":\"ALLOW\"}");
        ledger.append("AI_GATEWAY_INSPECTION_BLOCKED", actor, RESOURCE_TYPE, policy, "{\"verdict\":\"BLOCK\"}");
        ledger.append("SANITIZATION_RUN_CREATED", actor, "DATASET", policy, "{\"datasetId\":\"x\"}");
        ledger.append("DATASET_UPLOADED", actor, "DATASET", policy, "{\"rows\":1}");

        List<AuditLedgerEntry> found = history(actor, policy);

        assertThat(found).extracting(AuditLedgerEntry::getEventType)
                .containsExactly("GATEWAY_USAGE_POLICY_CREATED");
    }

    @Test
    void isolatesByActorSoOneOwnerNeverSeesAnothersHistory() {
        String mine = actor();
        String theirs = actor();
        UUID sharedPolicyId = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", mine, sharedPolicyId, "{\"action\":\"CREATED\"}");
        append("GATEWAY_USAGE_POLICY_UPDATED", theirs, sharedPolicyId, "{\"action\":\"UPDATED\"}");

        // The same resource id, two actors: each sees only their own entry.
        assertThat(history(mine, sharedPolicyId))
                .extracting(AuditLedgerEntry::getActorSubject)
                .containsExactly(mine);
        assertThat(history(theirs, sharedPolicyId))
                .extracting(AuditLedgerEntry::getActorSubject)
                .containsExactly(theirs);
    }

    @Test
    void isolatesByResourceIdSoOnePolicyNeverShowsAnotherPolicysHistory() {
        String actor = actor();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, first, "{\"action\":\"CREATED\"}");
        append("GATEWAY_USAGE_POLICY_UPDATED", actor, second, "{\"action\":\"UPDATED\"}");

        assertThat(history(actor, first))
                .extracting(AuditLedgerEntry::getResourceId)
                .containsExactly(first);
    }

    @Test
    void returnsNewestFirst() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");
        pause();
        append("GATEWAY_USAGE_POLICY_UPDATED", actor, policy, "{\"action\":\"UPDATED\"}");
        pause();
        append("GATEWAY_USAGE_POLICY_DELETED", actor, policy, "{\"action\":\"DELETED\"}");

        assertThat(history(actor, policy))
                .extracting(AuditLedgerEntry::getEventType)
                .containsExactly(
                        "GATEWAY_USAGE_POLICY_DELETED",
                        "GATEWAY_USAGE_POLICY_UPDATED",
                        "GATEWAY_USAGE_POLICY_CREATED");
        assertThat(history(actor, policy))
                .extracting(AuditLedgerEntry::getCreatedAt)
                .allSatisfy(instant -> assertThat(instant).isNotNull())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void tiesOnTimestampFallBackToTheSequenceNumber() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");
        append("GATEWAY_USAGE_POLICY_UPDATED", actor, policy, "{\"action\":\"UPDATED\"}");
        append("GATEWAY_USAGE_POLICY_DELETED", actor, policy, "{\"action\":\"DELETED\"}");
        // Force the tie the database is allowed to produce: three rows, one
        // timestamp. The sequence number is the total-order tiebreak, so the
        // result is newest-first and identical on every read.
        entities.createNativeQuery(
                        "UPDATE audit_ledger_entries SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00Z' "
                                + "WHERE actor_subject = '" + actor + "'")
                .executeUpdate();
        entities.flush();
        entities.clear();

        List<AuditLedgerEntry> firstRead = history(actor, policy);
        List<AuditLedgerEntry> secondRead = history(actor, policy);

        assertThat(firstRead).hasSize(3);
        assertThat(firstRead).extracting(AuditLedgerEntry::getEventType)
                .containsExactly(
                        "GATEWAY_USAGE_POLICY_DELETED",
                        "GATEWAY_USAGE_POLICY_UPDATED",
                        "GATEWAY_USAGE_POLICY_CREATED");
        // Deterministic: the same tie resolves the same way every time.
        assertThat(secondRead).extracting(AuditLedgerEntry::getSequenceNumber)
                .containsExactlyElementsOf(
                        firstRead.stream().map(AuditLedgerEntry::getSequenceNumber).toList());
    }

    @Test
    void historyOutlivesThePolicyItDescribes() {
        // The ledger is the historical record: no policy row is consulted, so
        // an id that was never a policy — exactly what a deleted one leaves
        // behind — still answers with its full trail.
        String actor = actor();
        UUID deletedPolicyId = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, deletedPolicyId, "{\"action\":\"CREATED\"}");
        pause();
        append("GATEWAY_USAGE_POLICY_DELETED", actor, deletedPolicyId, "{\"action\":\"DELETED\"}");

        List<AuditLedgerEntry> found = history(actor, deletedPolicyId);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(AuditLedgerEntry::getEventType)
                .containsExactly("GATEWAY_USAGE_POLICY_DELETED", "GATEWAY_USAGE_POLICY_CREATED");
    }

    @Test
    void theResultIsBoundedToOneHundredNewestEntries() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        for (int i = 0; i < 105; i++) {
            append("GATEWAY_USAGE_POLICY_UPDATED", actor, policy, "{\"action\":\"UPDATED\"}");
        }

        List<AuditLedgerEntry> found = history(actor, policy);

        // The bound is applied by the database query, not by trimming in Java,
        // and it keeps the newest 100 rather than an arbitrary 100.
        assertThat(found).hasSize(100);
        assertThat(found.get(0).getSequenceNumber())
                .isGreaterThan(found.get(99).getSequenceNumber());
    }

    @Test
    void anUnknownActorGetsAnEmptyHistory() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");

        assertThat(history(actor() + "-nobody", policy)).isEmpty();
    }

    @Test
    void anUnknownResourceGetsAnEmptyHistory() {
        assertThat(history(actor(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void anotherResourceTypeIsNotReachableThroughThePolicyQuery() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        // Right actor, right id, wrong resource type: a policy history must
        // not pick it up.
        ledger.append("GATEWAY_USAGE_POLICY_CREATED", actor, "DATASET", policy, "{\"action\":\"CREATED\"}");

        assertThat(history(actor, policy)).isEmpty();
    }

    @Test
    void theHistoryReadLeavesTheChainIntact() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");
        long before = entries.count();

        history(actor, policy);
        history(actor, policy);

        // Reading is reading: no entry is appended, rewritten, or removed, and
        // the hash chain still verifies.
        assertThat(entries.count()).isEqualTo(before);
        assertThat(new AuditLedgerVerificationService(entries).verify().valid()).isTrue();
    }

    @Test
    void everyReturnedRowCarriesAStoredTimestamp() {
        String actor = actor();
        UUID policy = UUID.randomUUID();
        append("GATEWAY_USAGE_POLICY_CREATED", actor, policy, "{\"action\":\"CREATED\"}");

        AuditLedgerEntry row = history(actor, policy).get(0);

        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
