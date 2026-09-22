package com.aegivault.aegivault.sanitization.run;

import java.time.Instant;
import java.util.UUID;

/**
 * Service-layer view of a {@link SanitizationRun}. Carries operation
 * metadata only: no CSV content, no PII, no samples, no secrets.
 */
public record SanitizationRunView(
        UUID id,
        UUID datasetId,
        String ownerSubject,
        RunStatus status,
        String policyName,
        String policyVersion,
        String policySnapshot,
        Long inputRowCount,
        Long outputRowCount,
        Long blankRowsSkipped,
        Integer columnCount,
        Instant startedAt,
        Instant completedAt,
        String errorCode,
        String errorStage,
        String errorMessage,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    static SanitizationRunView from(SanitizationRun run) {
        return new SanitizationRunView(
                run.getId(),
                run.getDataset().getId(),
                run.getOwnerSubject(),
                run.getStatus(),
                run.getPolicyName(),
                run.getPolicyVersion(),
                run.getPolicySnapshot(),
                run.getInputRowCount(),
                run.getOutputRowCount(),
                run.getBlankRowsSkipped(),
                run.getColumnCount(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getErrorCode(),
                run.getErrorStage(),
                run.getErrorMessage(),
                run.getVersion(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
