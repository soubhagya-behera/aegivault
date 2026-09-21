package com.aegivault.aegivault.pii.profile;

import com.aegivault.aegivault.pii.PiiType;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata describing PII observed in one dataset column.
 *
 * <p>Carries only counts, never raw sampled values or raw PII. Detection
 * counts record how many analyzed values contained each {@link PiiType};
 * detection rates are the observed fraction of analyzed non-blank values
 * containing that type (not confidence, not accuracy). A column with no
 * analyzable values reports empty detections and zero rates.
 */
public record ColumnProfile(
        String columnName,
        int suppliedValueCount,
        int analyzedValueCount,
        int analyzableValueCount,
        Map<PiiType, Integer> detectionCounts,
        Map<PiiType, Double> detectionRates,
        Set<PiiType> detectedTypes) {

    public ColumnProfile {
        Objects.requireNonNull(columnName, "columnName must not be null");
        Objects.requireNonNull(detectionCounts, "detectionCounts must not be null");
        Objects.requireNonNull(detectionRates, "detectionRates must not be null");
        Objects.requireNonNull(detectedTypes, "detectedTypes must not be null");
        if (suppliedValueCount < 0 || analyzedValueCount < 0 || analyzableValueCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        detectionCounts = Map.copyOf(detectionCounts);
        detectionRates = Map.copyOf(detectionRates);
        detectedTypes = Set.copyOf(detectedTypes);
    }
}
