package com.aegivault.aegivault.audit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only writer for the audit ledger. Each call appends exactly one
 * entry in one transaction: the current tail supplies the sequence and the
 * previous hash, the entry carries the hash of its own content, and the
 * row is flushed before returning so the view reflects the persisted row.
 *
 * <p>The first entry uses the deterministic previous hash
 * {@code GENESIS} — never a random value — so an empty database always
 * bootstraps the same chain head shape.
 *
 * <p>Concurrency: single-instance architecture. Two concurrent appends
 * read the same tail and the loser fails loudly on the UNIQUE constraint
 * instead of forking the chain — but there is no distributed sequencing
 * (no Redis, no workers, no locks). Multi-instance deployment needs an
 * explicit sequencing decision first; until then the constraint is the
 * backstop, not a queue.
 *
 * <p>Callers pass safe metadata only. This service stores exactly what it
 * is given — it cannot see meaning — so only explicit, reviewed call
 * sites may append, each with a fixed metadata document.
 */
@Service
@RequiredArgsConstructor
public class AuditLedgerService {

    /**
     * Deterministic previous hash of the first ledger entry. A constant,
     * so every fresh database bootstraps the identical chain head.
     */
    public static final String GENESIS_PREVIOUS_HASH = "GENESIS";

    private final AuditLedgerEntryRepository entries;

    /**
     * Appends one entry for the given event.
     *
     * @param eventType what happened, never blank, at most 128 characters
     * @param actorSubject who acted, never blank, at most 255 characters
     * @param resourceType what the event concerned, never blank, at most
     *        128 characters
     * @param resourceId which resource, or null when the event concerns no
     *        single row
     * @param eventData safe metadata document, never null, at most 8192
     *        UTF-8 bytes — never raw CSV, PII, secrets, or request bodies
     * @return the persisted entry
     * @throws IllegalArgumentException when the entry violates its bounds
     */
    @Transactional
    public AuditLedgerEntryView append(
            String eventType,
            String actorSubject,
            String resourceType,
            UUID resourceId,
            String eventData) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(actorSubject, "actorSubject must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(eventData, "eventData must not be null");
        AuditLedgerEntry tail = entries.findTopByOrderBySequenceNumberDesc().orElse(null);
        long sequenceNumber = tail == null ? 1L : tail.getSequenceNumber() + 1L;
        String previousHash = tail == null ? GENESIS_PREVIOUS_HASH : tail.getEntryHash();
        AuditLedgerEntry entry = new AuditLedgerEntry(
                sequenceNumber, eventType, actorSubject, resourceType,
                resourceId, eventData, previousHash);
        return AuditLedgerEntryView.from(entries.saveAndFlush(entry));
    }

    /**
     * Reads the whole chain in sequence order. Used by verification; there
     * is no paged access yet because the ledger is small and replay must
     * see every entry.
     */
    @Transactional(readOnly = true)
    public List<AuditLedgerEntryView> list() {
        return entries.findAllByOrderBySequenceNumberAsc().stream()
                .map(AuditLedgerEntryView::from)
                .toList();
    }
}
