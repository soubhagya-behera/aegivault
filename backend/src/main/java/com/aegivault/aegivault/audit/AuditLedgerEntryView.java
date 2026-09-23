package com.aegivault.aegivault.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of one {@link AuditLedgerEntry}: chain position, safe event
 * metadata, and the hash links. Carries no persistence details and — like
 * every other view in this codebase — no raw data, because the ledger
 * stores none.
 */
public record AuditLedgerEntryView(
        UUID id,
        long sequenceNumber,
        String eventType,
        String actorSubject,
        String resourceType,
        UUID resourceId,
        String eventData,
        String previousHash,
        String entryHash,
        Instant createdAt) {

    static AuditLedgerEntryView from(AuditLedgerEntry entry) {
        return new AuditLedgerEntryView(
                entry.getId(),
                entry.getSequenceNumber(),
                entry.getEventType(),
                entry.getActorSubject(),
                entry.getResourceType(),
                entry.getResourceId(),
                entry.getEventData(),
                entry.getPreviousHash(),
                entry.getEntryHash(),
                entry.getCreatedAt());
    }
}
