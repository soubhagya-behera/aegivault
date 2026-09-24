package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * API view of a stored dataset profile: the profiler result, read back
 * verbatim. Carries dataset and column metadata plus detection
 * counts/rates/types only — never raw CSV values, samples, or PII values,
 * because nothing in the stored aggregate holds any.
 *
 * <p>{@code ownerSubject} is deliberately excluded: it is an internal
 * authorization field and every endpoint here is already scoped to the
 * authenticated owner.
 *
 * @param datasetId profiled dataset id
 * @param columns column views in the profiler's deterministic column-name
 *        order
 * @param totalColumns column count reported by the profiler
 * @param maxSampleSizePerColumn per-column sample limit reported by the
 *        profiler
 */
public record DatasetProfileResponse(
        UUID datasetId,
        List<ColumnProfileResponse> columns,
        int totalColumns,
        int maxSampleSizePerColumn) {

    static DatasetProfileResponse from(DatasetProfile profile) {
        return new DatasetProfileResponse(
                profile.datasetId(),
                profile.columns().stream().map(ColumnProfileResponse::from).toList(),
                profile.totalColumns(),
                profile.maxSampleSizePerColumn());
    }

    /**
     * API view of one stored profiled column: counts and detections only.
     *
     * @param columnName column name as profiled
     * @param suppliedValueCount values supplied to the profiler
     * @param analyzedValueCount values the profiler analyzed (bounded sample)
     * @param analyzableValueCount analyzed non-blank values (rate denominator)
     * @param detectionCounts per-type detection counts, alphabetical by type
     * @param detectionRates per-type observed detection rates, alphabetical
     * @param detectedTypes detected types, alphabetical
     */
    public record ColumnProfileResponse(
            String columnName,
            int suppliedValueCount,
            int analyzedValueCount,
            int analyzableValueCount,
            Map<PiiType, Integer> detectionCounts,
            Map<PiiType, Double> detectionRates,
            Set<PiiType> detectedTypes) {

        static ColumnProfileResponse from(ColumnProfile column) {
            Map<PiiType, Integer> counts = new TreeMap<>(Comparator.comparing(PiiType::name));
            counts.putAll(column.detectionCounts());
            Map<PiiType, Double> rates = new TreeMap<>(Comparator.comparing(PiiType::name));
            rates.putAll(column.detectionRates());
            Set<PiiType> types = new TreeSet<>(Comparator.comparing(PiiType::name));
            types.addAll(column.detectedTypes());
            return new ColumnProfileResponse(
                    column.columnName(),
                    column.suppliedValueCount(),
                    column.analyzedValueCount(),
                    column.analyzableValueCount(),
                    Map.copyOf(counts),
                    Map.copyOf(rates),
                    Set.copyOf(types));
        }
    }
}
