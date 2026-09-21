package com.aegivault.aegivault.dataset.csv;

/**
 * Domain-level failure for CSV input that cannot be discovered safely:
 * empty input, a missing or blank header, a duplicate header name, malformed
 * quoting, a row width that contradicts the header, or a violated safety
 * limit (columns, field length, input bytes, requested sample size).
 *
 * <p>CSV contents are treated as sensitive: messages name structural facts
 * only — row numbers, column indexes, counts, and the violated limit. Field
 * values, header names, and whole rows are never included.
 */
public class CsvParseException extends RuntimeException {

    public CsvParseException(String message) {
        super(message);
    }
}
