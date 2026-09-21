package com.aegivault.aegivault.pii.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Profiles a collection of columns into one deterministic {@link DatasetProfile}.
 *
 * <p>Delegates each column to {@link PiiColumnProfiler} and orders the
 * resulting column profiles by column name so output is stable regardless of
 * input order. Accepts an optional dataset identifier for correlation; the
 * profile is never persisted here. Not connected to CSV upload or the Dataset
 * REST API in this milestone.
 */
@Component
public class DatasetProfiler {

    private final PiiColumnProfiler columnProfiler;

    public DatasetProfiler(PiiColumnProfiler columnProfiler) {
        this.columnProfiler = Objects.requireNonNull(columnProfiler, "columnProfiler must not be null");
    }

    public DatasetProfile profile(UUID datasetId, List<ColumnInput> columns) {
        Objects.requireNonNull(columns, "columns must not be null");
        List<ColumnProfile> profiles = new ArrayList<>();
        for (ColumnInput column : columns) {
            Objects.requireNonNull(column, "column must not be null");
            profiles.add(columnProfiler.profile(column));
        }
        profiles.sort(Comparator.comparing(ColumnProfile::columnName));
        return new DatasetProfile(
                datasetId,
                List.copyOf(profiles),
                profiles.size(),
                columnProfiler.maxSampleSize());
    }

    /**
     * Maximum number of values analysed per column, as configured on the
     * underlying {@link PiiColumnProfiler}. Callers that supply values (for
     * example CSV discovery) use this to decide how many rows to read instead
     * of inventing a separate sampling limit.
     *
     * @return the effective per-column sample limit
     */
    public int maxSampleSizePerColumn() {
        return columnProfiler.maxSampleSize();
    }
}
