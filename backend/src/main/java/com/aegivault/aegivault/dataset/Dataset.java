package com.aegivault.aegivault.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Aggregate root of ingested data. Mapped 1:1 to the Flyway-managed
 * {@code datasets} table (V1); Hibernate never modifies the schema
 * ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "datasets")
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Setter
    @Column(name = "source_type", nullable = false)
    private String sourceType = "CSV";

    @Setter
    @Column(name = "original_filename")
    private String originalFilename;

    @Setter
    @Column(name = "row_count")
    private Long rowCount;

    @Setter
    @Column(name = "status", nullable = false)
    private String status = "UPLOADED";

    @Setter
    @Column(name = "owner_subject")
    private String ownerSubject;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Dataset(String name, String ownerSubject) {
        this.name = name;
        this.ownerSubject = ownerSubject;
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
