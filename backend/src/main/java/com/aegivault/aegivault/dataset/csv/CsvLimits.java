package com.aegivault.aegivault.dataset.csv;

import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;

/**
 * Explicit safety limits for CSV discovery.
 *
 * <p>Deliberately small and few: a column ceiling, a sampled-row ceiling, a
 * per-field length ceiling, and an input-byte ceiling. The defaults are
 * conservative development-foundation values, not a claim of complete
 * denial-of-service protection: they bound the work and memory of one
 * discovery call, nothing more.
 *
 * <p>The default sampled-row ceiling reuses
 * {@link PiiColumnProfiler#DEFAULT_MAX_SAMPLE_SIZE} so the CSV layer samples
 * exactly the number of values the profiling layer analyses by default,
 * instead of inventing a second, unrelated sampling concept.
 *
 * @param maxColumns     maximum fields accepted in any one CSV record
 * @param maxSampledRows maximum data rows retained per column for profiling
 * @param maxFieldLength maximum characters accepted in a single field
 * @param maxInputBytes  maximum bytes accepted from the CSV input stream
 */
public record CsvLimits(int maxColumns, int maxSampledRows, int maxFieldLength, long maxInputBytes) {

    public static final int DEFAULT_MAX_COLUMNS = 100;

    public static final int DEFAULT_MAX_SAMPLED_ROWS = PiiColumnProfiler.DEFAULT_MAX_SAMPLE_SIZE;

    public static final int DEFAULT_MAX_FIELD_LENGTH = 10_000;

    public static final long DEFAULT_MAX_INPUT_BYTES = 10L * 1024L * 1024L;

    public CsvLimits {
        if (maxColumns < 1) {
            throw new IllegalArgumentException("maxColumns must be at least 1");
        }
        if (maxSampledRows < 1) {
            throw new IllegalArgumentException("maxSampledRows must be at least 1");
        }
        if (maxFieldLength < 1) {
            throw new IllegalArgumentException("maxFieldLength must be at least 1");
        }
        if (maxInputBytes < 1) {
            throw new IllegalArgumentException("maxInputBytes must be at least 1");
        }
    }

    /**
     * Default limits for local development.
     *
     * @return a fresh instance of the default limits
     */
    public static CsvLimits defaults() {
        return new CsvLimits(
                DEFAULT_MAX_COLUMNS, DEFAULT_MAX_SAMPLED_ROWS, DEFAULT_MAX_FIELD_LENGTH, DEFAULT_MAX_INPUT_BYTES);
    }
}
