package com.aegivault.aegivault.dataset.csv;

import java.util.Objects;

/**
 * Bounded immutable result of one CSV sanitization run: structural counts only.
 *
 * <p>Carries no cell values, no header names, and no profile data — only the
 * column count, the number of non-blank data rows transformed and written, and
 * the number of all-blank data records skipped by policy. The raw-data lifetime
 * stays bounded to the in-memory call: values are read once, transformed, and
 * streamed out without being retained here.
 *
 * @param columnCount     columns per record, taken from the accepted header
 * @param dataRowsWritten non-blank data rows transformed and written
 * @param blankRowsSkipped all-blank data records skipped by policy
 */
public record CsvSanitizationResult(int columnCount, long dataRowsWritten, long blankRowsSkipped) {

    public CsvSanitizationResult {
        if (columnCount < 1) {
            throw new IllegalArgumentException("columnCount must be at least 1");
        }
        if (dataRowsWritten < 0) {
            throw new IllegalArgumentException("dataRowsWritten must not be negative");
        }
        if (blankRowsSkipped < 0) {
            throw new IllegalArgumentException("blankRowsSkipped must not be negative");
        }
    }

    /**
     * @return total non-blank data rows read, equal to rows written
     */
    public long dataRowsRead() {
        return dataRowsWritten;
    }
}
