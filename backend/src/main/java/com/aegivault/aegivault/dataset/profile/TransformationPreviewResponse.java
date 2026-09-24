package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Read-only preview of which transformation strategy would apply to each
 * PII type detected in a persisted dataset profile.
 *
 * <p>Built from the stored {@link DatasetProfileResponse} plus an explicit
 * {@link TransformationPlan} — callers pass the existing default policy, so
 * this view never invents a strategy. It carries profile metadata
 * (per-type counts and observed rates) plus strategy recommendations only:
 * no raw CSV values, samples, PII values, sanitized values, owner, policy
 * internals, or database entities, because none of those reach this
 * mapping.
 *
 * <p>{@code ownerSubject} is deliberately excluded: it is an internal
 * authorization field and every endpoint here is already scoped to the
 * authenticated owner.
 *
 * @param datasetId profiled dataset id
 * @param columns one view per profiled column, in the profiler's
 *        deterministic column-name order
 */
public record TransformationPreviewResponse(UUID datasetId, List<ColumnPreviewResponse> columns) {

    static TransformationPreviewResponse from(DatasetProfileResponse profile, TransformationPlan plan) {
        return new TransformationPreviewResponse(
                profile.datasetId(),
                profile.columns().stream()
                        .map(column -> ColumnPreviewResponse.from(column, plan))
                        .toList());
    }

    /**
     * Preview of one profiled column: the detected types with their stored
     * counts/rates and the strategy the plan suggests for each. Columns
     * with no detections carry an empty list.
     *
     * @param columnName column name as profiled
     * @param detectedTypes per-type previews, alphabetical by type
     */
    public record ColumnPreviewResponse(String columnName, List<DetectedTypePreviewResponse> detectedTypes) {

        static ColumnPreviewResponse from(
                DatasetProfileResponse.ColumnProfileResponse column, TransformationPlan plan) {
            List<DetectedTypePreviewResponse> detections = column.detectedTypes().stream()
                    .sorted(Comparator.comparing(PiiType::name))
                    .map(type -> new DetectedTypePreviewResponse(
                            type,
                            column.detectionCounts().get(type),
                            column.detectionRates().get(type),
                            plan.strategyFor(type)
                                    .orElseThrow(() -> new MissingTransformationException(type))))
                    .toList();
            return new ColumnPreviewResponse(column.columnName(), detections);
        }
    }

    /**
     * Preview of one detected PII type: what the persisted profile recorded
     * plus what the plan suggests. Counts and rates are copied from the
     * stored profile; nothing is recomputed here.
     *
     * @param piiType detected PII type
     * @param detectionCount persisted per-type detection count
     * @param detectionRate persisted per-type observed detection rate
     * @param suggestedStrategy strategy the plan configures for the type;
     *        a recommendation only — nothing is executed, stored, or applied
     */
    public record DetectedTypePreviewResponse(
            PiiType piiType,
            int detectionCount,
            double detectionRate,
            TransformationStrategy suggestedStrategy) {}
}
