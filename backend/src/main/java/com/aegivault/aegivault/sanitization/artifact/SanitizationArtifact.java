package com.aegivault.aegivault.sanitization.artifact;

import com.aegivault.aegivault.sanitization.run.SanitizationRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Sanitized CSV output bytes of one {@link SanitizationRun}: one row per
 * run, keyed by the run id.
 *
 * <p>Deliberately separate from {@link SanitizationRun}: the run entity
 * holds no reference here and this entity holds no object association
 * back — only the raw run id, with the foreign key enforced by the schema
 * — so run metadata queries can never accidentally load a large payload,
 * and no ORM navigation can pull it in. Bytes leave only as fresh streams
 * through the artifact store.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code sanitization_artifacts} table
 * (V5); Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "sanitization_artifacts")
public class SanitizationArtifact {

    @Id
    @Column(name = "run_id", updatable = false, nullable = false)
    private UUID runId;

    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @param runId id of the owning persisted run, never null
     * @param ownerSubject owner copied from the run, never blank
     * @param content sanitized bytes (defensively copied), never null
     */
    public SanitizationArtifact(UUID runId, String ownerSubject, byte[] content) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.ownerSubject = requireText(ownerSubject, "ownerSubject");
        this.content = copyOf(Objects.requireNonNull(content, "content must not be null"));
    }

    void replaceContent(byte[] content) {
        this.content = copyOf(Objects.requireNonNull(content, "content must not be null"));
    }

    /**
     * @return a defensive copy; callers never touch the managed array
     */
    public byte[] contentCopy() {
        return copyOf(content);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    private static byte[] copyOf(byte[] content) {
        return Arrays.copyOf(content, content.length);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
