package com.aegivault.aegivault.dataset.postgres.binding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One Aegivault dataset bound to one discovered PostgreSQL base table. Mapped
 * 1:1 to the Flyway-managed {@code postgres_dataset_bindings} table (V12);
 * Hibernate never modifies the schema ({@code ddl-auto=validate}).
 *
 * <p><strong>Metadata only, by construction.</strong> The six persisted fields
 * are the dataset, its owner, and the table's schema and name. There is no
 * field for a host, port, database, username, password, or JDBC URL, so a
 * credential has nowhere to be stored here — the source's connection details
 * stay application configuration. There is likewise no field for a value,
 * sample, row count, or content, so binding a dataset cannot persist a row.
 *
 * <p><strong>One binding per dataset.</strong> {@code dataset_id} is the primary
 * key, so a second binding for the same dataset is refused by the database
 * rather than leaving the source of a dataset ambiguous. Reassignment is an
 * explicit delete-then-bind, never a silent overwrite.
 *
 * <p><strong>Owner-scoped.</strong> {@code ownerSubject} is copied from the
 * dataset at bind time so owner-scoped reads need no join, and every lookup
 * path filters on it. Identifiers follow the same opaque-text convention as
 * {@code datasets}, {@code dataset_profiles}, and the run/artifact tables.
 *
 * <p>Timestamps are set on persist and update, matching {@code Dataset}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "postgres_dataset_bindings")
public class PostgresDatasetBinding {

    @Id
    @Column(name = "dataset_id", updatable = false, nullable = false)
    private UUID datasetId;

    @Column(name = "owner_subject", nullable = false, updatable = false)
    private String ownerSubject;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PostgresDatasetBinding(UUID datasetId, String ownerSubject, String schemaName, String tableName) {
        this.datasetId = datasetId;
        this.ownerSubject = ownerSubject;
        this.schemaName = schemaName;
        this.tableName = tableName;
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
}
