package com.aegivault.aegivault.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stored CSV input bytes of one {@link Dataset}: one row per dataset,
 * keyed by the dataset id.
 *
 * <p>This is a deliberately separate boundary from {@link Dataset} metadata:
 * the {@code Dataset} entity holds no reference to this table and this
 * entity holds no object association back — only the raw dataset id, with
 * the foreign key enforced by the schema — so dataset list/get operations
 * can never accidentally load up to 10 MiB of bytes, and no ORM navigation
 * can pull the payload in. Bytes are read only through
 * {@code DatasetInputRepository} by dataset id plus owner, served to
 * callers as fresh streams.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code dataset_inputs} table (V4);
 * Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "dataset_inputs")
public class DatasetInput {

    @Id
    @Column(name = "dataset_id", updatable = false, nullable = false)
    private UUID datasetId;

    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @param datasetId id of the owning persisted dataset, never null
     * @param ownerSubject owner copied from the dataset, never blank
     * @param content stored bytes (defensively copied), never null
     */
    public DatasetInput(UUID datasetId, String ownerSubject, byte[] content) {
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId must not be null");
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
        return java.util.Arrays.copyOf(content, content.length);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
