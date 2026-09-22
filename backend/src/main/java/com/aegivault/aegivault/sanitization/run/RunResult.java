package com.aegivault.aegivault.sanitization.run;

/**
 * Safe structural result recorded when a run completes. Counts only: no
 * column names, no samples, no cell values, no raw data of any kind.
 *
 * @param inputRows rows read from the source, never negative
 * @param outputRows rows written to the sanitized output, never negative
 * @param blankRowsSkipped blank records skipped by policy, never negative
 * @param columnCount columns per record, at least 1
 */
public record RunResult(long inputRows, long outputRows, long blankRowsSkipped, int columnCount) {

    public RunResult {
        if (inputRows < 0) {
            throw new IllegalArgumentException("inputRows must not be negative");
        }
        if (outputRows < 0) {
            throw new IllegalArgumentException("outputRows must not be negative");
        }
        if (blankRowsSkipped < 0) {
            throw new IllegalArgumentException("blankRowsSkipped must not be negative");
        }
        if (columnCount < 1) {
            throw new IllegalArgumentException("columnCount must be at least 1");
        }
    }
}
