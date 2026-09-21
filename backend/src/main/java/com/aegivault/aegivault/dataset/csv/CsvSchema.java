package com.aegivault.aegivault.dataset.csv;

import java.util.List;
import java.util.Objects;

/**
 * Immutable schema discovered from a CSV header record.
 *
 * <p>Carries metadata only: the ordered column names as found, the derived
 * column count, and whether a header row was present. No cell values are
 * retained here, and no row or sample counts are claimed here — those live on
 * {@link CsvSample}, which also owns the bounded column samples.
 *
 * <p>Column names are preserved verbatim (never trimmed, renamed, or made
 * unique) so a discovered schema always describes the input exactly.
 *
 * @param columnNames   ordered column names, at least one, none blank
 * @param headerPresent {@code true} when the first record was used as the header
 */
public record CsvSchema(List<String> columnNames, boolean headerPresent) {

    public CsvSchema {
        Objects.requireNonNull(columnNames, "columnNames must not be null");
        if (columnNames.isEmpty()) {
            throw new IllegalArgumentException("columnNames must not be empty");
        }
        for (String columnName : columnNames) {
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("column names must not be blank");
            }
        }
        columnNames = List.copyOf(columnNames);
    }

    /**
     * Number of discovered columns.
     *
     * @return the size of {@link #columnNames()}
     */
    public int columnCount() {
        return columnNames.size();
    }
}
