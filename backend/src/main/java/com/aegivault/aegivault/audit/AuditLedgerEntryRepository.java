package com.aegivault.aegivault.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link AuditLedgerEntry}. Standard CRUD comes from
 * {@link JpaRepository}; the ledger adds exactly two access paths, both
 * uncovered by the primary key: the chain tail (for appending) and the
 * whole chain in sequence order (for verification). Both are served by the
 * {@code sequence_number} UNIQUE constraint — no extra index needed for
 * them. Resource and actor lookups use the dedicated V7 indexes.
 */
public interface AuditLedgerEntryRepository extends JpaRepository<AuditLedgerEntry, UUID> {

    Optional<AuditLedgerEntry> findTopByOrderBySequenceNumberDesc();

    List<AuditLedgerEntry> findAllByOrderBySequenceNumberAsc();
}
