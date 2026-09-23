package com.aegivault.aegivault.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/**
 * Ledger append, persistence, and verification against real PostgreSQL:
 * GENESIS bootstrap, hash linkage, verbatim metadata storage, tamper
 * detection through direct row edits, and safe empty-ledger behavior.
 * Services are constructed directly over the repository — no web layer,
 * no REST endpoints exist for the ledger.
 *
 * <p>Row edits bypass the persistence context on purpose (that is what
 * tampering means), so every edit is followed by a flush plus clear
 * before verification re-reads the rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AuditLedgerServiceTest {

    @Autowired
    private AuditLedgerEntryRepository entries;

    @PersistenceContext
    private EntityManager entities;

    private AuditLedgerService ledger;

    private AuditLedgerVerificationService verification;

    @BeforeEach
    void services() {
        ledger = new AuditLedgerService(entries);
        verification = new AuditLedgerVerificationService(entries);
        // The ledger table is global and other suites commit rows into it,
        // so every test starts from a pristine chain. The surrounding test
        // transaction rolls the wipe back afterwards.
        entities.createNativeQuery("DELETE FROM audit_ledger_entries").executeUpdate();
        entities.flush();
        entities.clear();
    }

    private AuditLedgerEntryView append(String eventType, String actor, String data) {
        return ledger.append(
                eventType, actor, "DATASET", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                data);
    }

    private void tamper(String sql) {
        entities.createNativeQuery(sql).executeUpdate();
        entities.flush();
        entities.clear();
    }

    @Test
    void firstEntryUsesGenesisWithSequenceOne() {
        AuditLedgerEntryView first = append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");

        assertThat(first.sequenceNumber()).isEqualTo(1L);
        assertThat(first.previousHash()).isEqualTo(AuditLedgerService.GENESIS_PREVIOUS_HASH);
        assertThat(first.previousHash()).isEqualTo("GENESIS");
        assertThat(first.entryHash()).matches("[0-9a-f]{64}");
        assertThat(first.createdAt()).isNotNull();
    }

    @Test
    void secondEntryReferencesFirstEntryHash() {
        AuditLedgerEntryView first = append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");
        AuditLedgerEntryView second = append("DATASET_SANITIZED", "owner-1", "{\"rows\":10}");

        assertThat(second.sequenceNumber()).isEqualTo(2L);
        assertThat(second.previousHash()).isEqualTo(first.entryHash());
        assertThat(second.entryHash()).isNotEqualTo(first.entryHash());
    }

    @Test
    void storedHashMatchesIndependentRecomputation() {
        AuditLedgerEntryView first = append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");

        String recomputed = AuditEntryHasher.hash(
                first.sequenceNumber(), first.eventType(), first.actorSubject(),
                first.resourceType(), first.resourceId(), first.eventData(),
                first.previousHash());

        assertThat(first.entryHash()).isEqualTo(recomputed);
    }

    @Test
    void storedEventDataIsExactlyTheMetadataPassed() {
        String metadata = "{\"rows\":10,\"columns\":2}";

        AuditLedgerEntryView first = append("DATASET_UPLOADED", "owner-1", metadata);

        String stored = entities
                .createNativeQuery(
                        "SELECT event_data FROM audit_ledger_entries WHERE id = '" + first.id() + "'")
                .getSingleResult()
                .toString();
        assertThat(stored).isEqualTo(metadata);
        assertThat(stored).doesNotContain("password", "secret", "token");
    }

    @Test
    void multipleEntriesVerifySuccessfully() {
        List<AuditLedgerEntryView> appended = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            appended.add(append("EVENT_" + index, "owner-1", "{\"index\":" + index + "}"));
        }

        assertThat(appended).hasSize(5);
        assertThat(appended.stream().map(AuditLedgerEntryView::sequenceNumber))
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.failedSequenceNumber()).isNull();
    }

    @Test
    void emptyLedgerVerifiesValid() {
        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.failedSequenceNumber()).isNull();
    }

    @Test
    void modifiedEventDataIsDetected() {
        append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");
        append("DATASET_SANITIZED", "owner-1", "{\"rows\":10}");

        tamper("UPDATE audit_ledger_entries SET event_data = '{\"rows\":999}' WHERE sequence_number = 1");

        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequenceNumber()).isEqualTo(1L);
        assertThat(result.failureReason()).isEqualTo("ENTRY_HASH_MISMATCH");
    }

    @Test
    void modifiedEntryHashIsDetected() {
        append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");

        tamper("UPDATE audit_ledger_entries SET entry_hash = '"
                + "0".repeat(64) + "' WHERE sequence_number = 1");

        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequenceNumber()).isEqualTo(1L);
        assertThat(result.failureReason()).isEqualTo("ENTRY_HASH_MISMATCH");
    }

    @Test
    void modifiedPreviousHashIsDetected() {
        append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");
        append("DATASET_SANITIZED", "owner-1", "{\"rows\":10}");

        tamper("UPDATE audit_ledger_entries SET previous_hash = 'FORGED' WHERE sequence_number = 2");

        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequenceNumber()).isEqualTo(2L);
    }

    @Test
    void rewrittenLinkWithConsistentHashesIsStillDetected() {
        // The strongest forgery the chain itself can catch: entry 2 is
        // re-hashed consistently over a forged previous hash, so its own
        // recomputation passes — but it no longer links to entry 1.
        append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");
        AuditLedgerEntryView second =
                append("DATASET_SANITIZED", "owner-1", "{\"rows\":10}");
        String forged = AuditEntryHasher.hash(
                second.sequenceNumber(), second.eventType(), second.actorSubject(),
                second.resourceType(), second.resourceId(), second.eventData(), "FORGED");

        tamper("UPDATE audit_ledger_entries SET previous_hash = 'FORGED', entry_hash = '"
                + forged + "' WHERE sequence_number = 2");

        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequenceNumber()).isEqualTo(2L);
        assertThat(result.failureReason()).isEqualTo("PREVIOUS_HASH_LINK_BROKEN");
    }

    @Test
    void brokenSequenceIsDetected() {
        append("DATASET_UPLOADED", "owner-1", "{\"rows\":10}");
        append("DATASET_SANITIZED", "owner-1", "{\"rows\":10}");
        append("RUN_COMPLETED", "owner-1", "{\"runs\":1}");

        tamper("DELETE FROM audit_ledger_entries WHERE sequence_number = 2");

        AuditVerificationResult result = verification.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequenceNumber()).isEqualTo(3L);
        assertThat(result.failureReason()).isEqualTo("SEQUENCE_GAP");
    }
}
