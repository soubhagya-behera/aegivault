package com.aegivault.aegivault.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row of the tamper-evident audit ledger: who acted, what happened,
 * what it concerned, safe metadata only, and the hash-chain links.
 *
 * <p>An entry is immutable after creation — there are no setters, the
 * chain columns are {@code updatable = false}, and no service offers an
 * update or delete path. The {@code entryHash} is derived inside the
 * constructor from the canonical fields plus {@code previousHash}, never
 * supplied by a caller, so a stored entry always carries the hash of its
 * own content.
 *
 * <p>Safety is structural but partial: the entity bounds every field the
 * way the V7 CHECKs do, but "metadata only" is a caller contract the
 * schema cannot see — only explicit, reviewed call sites may append, and
 * each passes a fixed metadata document, never raw CSV, PII, secrets, or
 * request bodies.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code audit_ledger_entries} table
 * (V7); Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "audit_ledger_entries")
public class AuditLedgerEntry {

    private static final int TYPE_MAX = 128;

    private static final int ACTOR_MAX = 255;

    private static final int EVENT_DATA_MAX_BYTES = 8192;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sequence_number", updatable = false, nullable = false)
    private long sequenceNumber;

    @Column(name = "event_type", updatable = false, nullable = false, length = TYPE_MAX)
    private String eventType;

    @Column(name = "actor_subject", updatable = false, nullable = false, length = ACTOR_MAX)
    private String actorSubject;

    @Column(name = "resource_type", updatable = false, nullable = false, length = TYPE_MAX)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "event_data", updatable = false, nullable = false)
    private String eventData;

    @Column(name = "previous_hash", updatable = false, nullable = false)
    private String previousHash;

    @Column(name = "entry_hash", updatable = false, nullable = false)
    private String entryHash;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /**
     * Creates one chain entry with its hash derived from its own content.
     *
     * @param sequenceNumber 1-based position in the chain, at least 1
     * @param eventType what happened, never blank, at most 128 characters
     * @param actorSubject who acted, never blank, at most 255 characters
     * @param resourceType what the event concerned, never blank, at most
     *        128 characters
     * @param resourceId which resource, or null when the event concerns no
     *        single row
     * @param eventData safe metadata document, never null, at most 8192
     *        UTF-8 bytes, stored verbatim — callers must never pass raw
     *        CSV, PII, secrets, or request bodies
     * @param previousHash entry hash of the previous sequence, or
     *        {@code GENESIS} for the first entry, never blank
     * @throws IllegalArgumentException when any bound is violated
     */
    public AuditLedgerEntry(
            long sequenceNumber,
            String eventType,
            String actorSubject,
            String resourceType,
            UUID resourceId,
            String eventData,
            String previousHash) {
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be at least 1");
        }
        this.sequenceNumber = sequenceNumber;
        this.eventType = requireText(eventType, "eventType", TYPE_MAX);
        this.actorSubject = requireText(actorSubject, "actorSubject", ACTOR_MAX);
        this.resourceType = requireText(resourceType, "resourceType", TYPE_MAX);
        this.resourceId = resourceId;
        this.eventData = requireData(eventData);
        this.previousHash = requireText(previousHash, "previousHash", 64);
        this.entryHash = AuditEntryHasher.hash(
                sequenceNumber, this.eventType, this.actorSubject, this.resourceType,
                resourceId, this.eventData, this.previousHash);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String requireData(String eventData) {
        Objects.requireNonNull(eventData, "eventData must not be null");
        if (eventData.getBytes(StandardCharsets.UTF_8).length > EVENT_DATA_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "eventData must be at most " + EVENT_DATA_MAX_BYTES + " bytes");
        }
        return eventData;
    }
}
