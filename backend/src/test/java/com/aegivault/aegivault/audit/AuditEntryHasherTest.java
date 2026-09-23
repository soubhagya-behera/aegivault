package com.aegivault.aegivault.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the ledger hash: an explicit, length-prefixed
 * canonical form hashed with SHA-256. No Spring, no database — the hash
 * must be a pure function of its inputs, so determinism and field
 * sensitivity are provable without any infrastructure.
 */
class AuditEntryHasherTest {

    private static final UUID RESOURCE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private static final String CANONICAL =
            "audit-ledger-v1|seq:1:1|type:16:DATASET_UPLOADED|actor:7:owner-1|resource:7:DATASET"
                    + "|resourceId:36:123e4567-e89b-12d3-a456-426614174000|data:11:{\"rows\":10}"
                    + "|prev:7:GENESIS";

    // SHA-256 of CANONICAL, computed independently (non-Java toolchain):
    // any change to the canonical format or the digest breaks this vector.
    private static final String EXPECTED_HASH =
            "3e7f623f8fefbafa66c7f2ecc4ce67706fa3bac11505026575010ac882638724";

    private static String hash() {
        return AuditEntryHasher.hash(
                1L, "DATASET_UPLOADED", "owner-1", "DATASET", RESOURCE_ID, "{\"rows\":10}", "GENESIS");
    }

    @Test
    void canonicalFormIsExplicitAndStable() {
        assertThat(AuditEntryHasher.canonicalEntry(
                        1L, "DATASET_UPLOADED", "owner-1", "DATASET",
                        RESOURCE_ID, "{\"rows\":10}", "GENESIS"))
                .isEqualTo(CANONICAL);
    }

    @Test
    void knownAnswerVector() {
        assertThat(hash()).isEqualTo(EXPECTED_HASH);
    }

    @Test
    void identicalInputsProduceIdenticalHashes() {
        assertThat(hash()).isEqualTo(hash());
        assertThat(hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void nullResourceIdCanonicalizesAsAnExplicitToken() {
        String canonical = AuditEntryHasher.canonicalEntry(
                1L, "DATASET_UPLOADED", "owner-1", "DATASET", null, "{\"rows\":10}", "GENESIS");

        assertThat(canonical).contains("|resourceId:4:null|");
        assertThat(AuditEntryHasher.hash(
                        1L, "DATASET_UPLOADED", "owner-1", "DATASET", null, "{\"rows\":10}", "GENESIS"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void everyFieldContributesToTheHash() {
        String base = hash();

        assertThat(AuditEntryHasher.hash(
                        2L, "DATASET_UPLOADED", "owner-1", "DATASET",
                        RESOURCE_ID, "{\"rows\":10}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(AuditEntryHasher.hash(
                        1L, "POLICY_CREATED", "owner-1", "DATASET",
                        RESOURCE_ID, "{\"rows\":10}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(AuditEntryHasher.hash(
                        1L, "DATASET_UPLOADED", "owner-2", "DATASET",
                        RESOURCE_ID, "{\"rows\":10}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(AuditEntryHasher.hash(
                        1L, "DATASET_UPLOADED", "owner-1", "POLICY",
                        RESOURCE_ID, "{\"rows\":10}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(AuditEntryHasher.hash(
                        1L, "DATASET_UPLOADED", "owner-1", "DATASET",
                        UUID.randomUUID(), "{\"rows\":10}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(AuditEntryHasher.hash(
                        1L, "DATASET_UPLOADED", "owner-1", "DATASET",
                        RESOURCE_ID, "{\"rows\":11}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(AuditEntryHasher.hash(
                        1L, "DATASET_UPLOADED", "owner-1", "DATASET",
                        RESOURCE_ID, "{\"rows\":10}", "OTHER"))
                .isNotEqualTo(base);
    }

    @Test
    void delimiterLookalikesCannotAliasAnotherTuple() {
        // Length-prefixing means a separator inside a value stays content:
        // "A|B" in one field never reads as two fields.
        String tricky = AuditEntryHasher.hash(
                1L, "A|B", "owner-1", "DATASET", RESOURCE_ID, "{\"rows\":10}", "GENESIS");
        String plain = AuditEntryHasher.hash(
                1L, "A", "B|owner-1", "DATASET", RESOURCE_ID, "{\"rows\":10}", "GENESIS");

        assertThat(tricky).isNotEqualTo(plain);
        assertThat(tricky).isNotEqualTo(hash());
    }
}
