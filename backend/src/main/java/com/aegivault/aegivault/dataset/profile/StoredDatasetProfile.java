package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.pii.profile.ColumnProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Aggregate root of one stored dataset profile: which dataset it describes
 * ({@code datasetId}, also the primary key, so at most one stored profile
 * exists per dataset), who owns it, and the dataset-level metadata copied
 * verbatim from the profiler result.
 *
 * <p>Columns are held as child entities in the profiler's deterministic
 * column-name order (their {@code columnOrdinal} preserves it), with
 * cascade-all plus orphan removal: replacing the column list deletes the
 * old rows, so a re-saved profile never leaves stale columns or
 * detections behind.
 *
 * <p>The aggregate carries metadata only — ids, names, counts, enum names,
 * and rates. It never holds raw CSV values, samples, PII values, or
 * sanitized values, because no such column exists in the schema.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code dataset_profiles} table (V8);
 * Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "dataset_profiles")
public class StoredDatasetProfile {

    @Id
    @Column(name = "dataset_id", updatable = false, nullable = false)
    private UUID datasetId;

    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    @Column(name = "total_columns", nullable = false)
    private int totalColumns;

    @Column(name = "max_sample_size_per_column", nullable = false)
    private int maxSampleSizePerColumn;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrdinal ASC")
    private List<StoredProfileColumn> columns = new ArrayList<>();

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Builds a stored profile from a profiler result.
     *
     * @param datasetId dataset the profile describes, never null; becomes
     *        the primary key
     * @param ownerSubject calling owner, never blank (the JWT subject only)
     * @param profile profiler result, never null; its column order is kept
     *        verbatim, so the profiler's deterministic column-name order is
     *        what the {@code columnOrdinal} values preserve
     */
    public StoredDatasetProfile(UUID datasetId, String ownerSubject, DatasetProfile profile) {
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId must not be null");
        this.ownerSubject = requireOwner(ownerSubject);
        Objects.requireNonNull(profile, "profile must not be null");
        replaceWith(profile);
    }

    /**
     * Replaces this profile's metadata and its entire column set in place.
     * The dataset id and owner never change, and no new profile row is
     * created. Removed columns (and their detections) are deleted by
     * orphan removal when the surrounding transaction commits.
     *
     * @param profile profiler result, never null
     */
    public void replaceWith(DatasetProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        List<StoredProfileColumn> next = columnsOf(profile);
        this.columns.clear();
        this.columns.addAll(next);
        this.totalColumns = profile.totalColumns();
        this.maxSampleSizePerColumn = profile.maxSampleSizePerColumn();
        this.updatedAt = Instant.now();
    }

    /**
     * Rebuilds the immutable profiler result from the stored rows, in the
     * stored column order.
     *
     * @return the profiler result; metadata and counts only
     */
    public DatasetProfile toProfile() {
        List<ColumnProfile> rebuilt = columns.stream()
                .sorted(Comparator.comparingInt(StoredProfileColumn::getColumnOrdinal))
                .map(StoredProfileColumn::toColumnProfile)
                .toList();
        return new DatasetProfile(datasetId, rebuilt, totalColumns, maxSampleSizePerColumn);
    }

    private List<StoredProfileColumn> columnsOf(DatasetProfile profile) {
        List<ColumnProfile> columns = profile.columns();
        List<StoredProfileColumn> stored = new ArrayList<>(columns.size());
        for (int ordinal = 0; ordinal < columns.size(); ordinal++) {
            stored.add(new StoredProfileColumn(this, ordinal, columns.get(ordinal)));
        }
        return stored;
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
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
