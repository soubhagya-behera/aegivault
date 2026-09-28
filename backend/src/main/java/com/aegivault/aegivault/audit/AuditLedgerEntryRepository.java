package com.aegivault.aegivault.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link AuditLedgerEntry}. Standard CRUD comes from
 * {@link JpaRepository}; the ledger adds exactly two chain access
 * paths, both uncovered by the primary key: the chain tail (for appending)
 * and the whole chain in sequence order (for verification).
 * <p>Both chain reads are served by the {@code sequence_number} UNIQUE
 * constraint — no extra index needed for them. Resource and actor lookups
 * use the dedicated V7 indexes. The ledger also adds exactly one scoped
 * history read for gateway usage policies: an actor-scoped, event-type
 * restricted, newest-first, database-bounded projection of one resource.
 * That read is {@code SELECT}-only and is the sole reason the ledger exposes
 * a query that is not a chain read; it never writes, and it deliberately
 * scopes by actor as well as resource so a caller-supplied resource id can
 * never widen the result set.
 */
public interface AuditLedgerEntryRepository extends JpaRepository<AuditLedgerEntry, UUID> {

    Optional<AuditLedgerEntry> findTopByOrderBySequenceNumberDesc();

    List<AuditLedgerEntry> findAllByOrderBySequenceNumberAsc();

    /**
     * Bounded, owner-scoped history of one resource: at most the 100 newest
     * entries that match the resource type, the resource id, <em>and</em> the
     * actor, restricted to the supplied event types, in deterministic
     * newest-first order ({@code created_at} descending, then
     * {@code sequence_number} descending, so rows sharing a timestamp still
     * come back in a stable order).
     *
     * <p>The actor filter is not optional decoration: scoping by resource id
     * alone would let one actor read another actor's history, and the
     * {@code resource_id} is attacker-supplied. The bound is applied in the
     * database query — the full history is never loaded and trimmed in Java.
     *
     * <p>No policy row is consulted, so this also answers "what happened to
     * the policy I deleted": the ledger, not the policy table, is the
     * historical record.
     */
    List<AuditLedgerEntry> findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
            String resourceType, UUID resourceId, String actorSubject, List<String> eventTypes);
}
