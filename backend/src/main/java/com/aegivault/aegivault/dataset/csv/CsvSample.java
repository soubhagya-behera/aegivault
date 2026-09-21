package com.aegivault.aegivault.dataset.csv;

import java.util.List;
import java.util.Objects;

/**
 * Bounded immutable result of CSV discovery: schema, per-column samples, and
 * explicit row accounting.
 *
 * <p>Each column sample holds exactly {@code sampledRowCount} values in
 * encounter order. {@code rowsEncountered} counts every data row read from the
 * accepted input; it can be larger than {@code sampledRowCount} when the
 * sample limit truncated what is retained. Both numbers describe the supplied
 * input only — discovery is bounded by the input-byte limit, and no full
 * dataset row count is invented for data that was never supplied.
 *
 * <p>Raw values are held only as long as the caller needs them for profiling;
 * they are never logged, persisted, or copied into a profile.
 *
 * @param schema          discovered header schema
 * @param columnSamples   one bounded sample list per column, never null
 * @param sampledRowCount data rows retained for profiling
 * @param rowsEncountered data rows read from the accepted input
 */
public record CsvSample(CsvSchema schema, List<List<String>> columnSamples, int sampledRowCount, long rowsEncountered) {

    public CsvSample {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(columnSamples, "columnSamples must not be null");
        if (sampledRowCount < 0) {
            throw new IllegalArgumentException("sampledRowCount must not be negative");
        }
        if (rowsEncountered < sampledRowCount) {
            throw new IllegalArgumentException("rowsEncountered must not be smaller than sampledRowCount");
        }
        if (columnSamples.size() != schema.columnCount()) {
            throw new IllegalArgumentException("columnSamples must match the schema column count");
        }
        for (List<String> column : columnSamples) {
            if (column == null || column.size() != sampledRowCount) {
                throw new IllegalArgumentException("each column sample must hold sampledRowCount values");
            }
        }
        columnSamples = columnSamples.stream().map(List::copyOf).toList();
    }

    /**
     * Whether the sample limit truncated the retained column values.
     *
     * @return {@code true} when more rows were read than were retained
     */
    public boolean sampleTruncated() {
        return rowsEncountered > sampledRowCount;
    }
}
