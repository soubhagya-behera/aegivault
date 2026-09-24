package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnProfile;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One stored column of a {@link StoredDatasetProfile}: its position in the
 * profiler's deterministic column-name order, its name, and the
 * supplied/analyzed/analyzable counts copied verbatim from the profiler
 * result.
 *
 * <p>Per-type detections ride along as an element collection in the
 * normalized {@code dataset_profile_detections} table — one row per
 * detected {@link PiiType} with its count and observed rate. A column with
 * no detections simply holds an empty collection.
 *
 * <p>The row carries column metadata only: names, counts, enum names, and
 * rates. It never holds raw CSV values, samples, or PII values, because no
 * such column exists in the schema.
 *
 * <p>The composite primary key {@code (dataset_id, column_ordinal)} is the
 * aggregate invariant enforced by storage: one row exists per column
 * position of one dataset's profile.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code dataset_profile_columns}
 * table (V8); Hibernate never modifies the schema
 * ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "dataset_profile_columns")
@IdClass(StoredProfileColumnKey.class)
public class StoredProfileColumn {

    @Id
    @Column(name = "dataset_id", updatable = false, nullable = false)
    private UUID datasetId;

    @Id
    @Column(name = "column_ordinal", updatable = false, nullable = false)
    private int columnOrdinal;

    @Column(name = "column_name", nullable = false)
    private String columnName;

    @Column(name = "supplied_value_count", nullable = false)
    private int suppliedValueCount;

    @Column(name = "analyzed_value_count", nullable = false)
    private int analyzedValueCount;

    @Column(name = "analyzable_value_count", nullable = false)
    private int analyzableValueCount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "dataset_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_dataset_profile_columns_profile"))
    @MapsId("datasetId")
    private StoredDatasetProfile profile;

    @ElementCollection
    @CollectionTable(
            name = "dataset_profile_detections",
            joinColumns = {
                @JoinColumn(name = "dataset_id", referencedColumnName = "dataset_id"),
                @JoinColumn(name = "column_ordinal", referencedColumnName = "column_ordinal")
            },
            foreignKey = @ForeignKey(name = "fk_dataset_profile_detections_column"))
    private List<StoredProfileDetection> detections = new ArrayList<>();

    /**
     * @param profile owning profile, never null; supplies the derived
     *        {@code datasetId}
     * @param columnOrdinal zero-based position in the profiler column order,
     *        never negative
     * @param column profiler result for this column, never null
     */
    public StoredProfileColumn(StoredDatasetProfile profile, int columnOrdinal, ColumnProfile column) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        if (columnOrdinal < 0) {
            throw new IllegalArgumentException("columnOrdinal must not be negative");
        }
        Objects.requireNonNull(column, "column must not be null");
        this.columnOrdinal = columnOrdinal;
        this.columnName = requireName(column.columnName());
        this.suppliedValueCount = requireCount(column.suppliedValueCount(), "suppliedValueCount");
        this.analyzedValueCount = requireCount(column.analyzedValueCount(), "analyzedValueCount");
        this.analyzableValueCount = requireCount(column.analyzableValueCount(), "analyzableValueCount");
        List<PiiType> ordered = column
                .detectionCounts()
                .keySet()
                .stream()
                .sorted(Comparator.comparing(PiiType::name))
                .toList();
        for (PiiType type : ordered) {
            this.detections.add(new StoredProfileDetection(
                    type,
                    column.detectionCounts().get(type),
                    column.detectionRates().getOrDefault(type, 0.0)));
        }
    }

    /**
     * Rebuilds the immutable profiler result for this column.
     *
     * @return the column profile; metadata and counts only
     */
    public ColumnProfile toColumnProfile() {
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        Map<PiiType, Double> rates = new EnumMap<>(PiiType.class);
        for (StoredProfileDetection detection : detections) {
            counts.put(detection.getPiiType(), detection.getDetectionCount());
            rates.put(detection.getPiiType(), detection.getDetectionRate());
        }
        Set<PiiType> detectedTypes = new TreeSet<>(Comparator.comparing(PiiType::name));
        detectedTypes.addAll(counts.keySet());
        return new ColumnProfile(
                columnName,
                suppliedValueCount,
                analyzedValueCount,
                analyzableValueCount,
                Map.copyOf(counts),
                Map.copyOf(rates),
                Set.copyOf(detectedTypes));
    }

    private static String requireName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        return columnName;
    }

    private static int requireCount(int count, String field) {
        if (count < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return count;
    }
}
