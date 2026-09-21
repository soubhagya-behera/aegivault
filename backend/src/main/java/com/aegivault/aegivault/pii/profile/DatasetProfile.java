package com.aegivault.aegivault.pii.profile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable metadata describing PII observed across a dataset's columns.
 *
 * <p>Domain/service foundation only: not persisted, with no database table in
 * this milestone. Columns are ordered deterministically by column name.
 * Carries only profiling metadata, never raw values or raw PII.
 */
public record DatasetProfile(
        UUID datasetId,
        List<ColumnProfile> columns,
        int totalColumns,
        int maxSampleSizePerColumn) {

    public DatasetProfile {
        Objects.requireNonNull(columns, "columns must not be null");
        if (totalColumns < 0) {
            throw new IllegalArgumentException("totalColumns must not be negative");
        }
        if (maxSampleSizePerColumn < 1) {
            throw new IllegalArgumentException("maxSampleSizePerColumn must be at least 1");
        }
        columns = List.copyOf(columns);
    }
}
