package com.aegivault.aegivault.dataset.postgres;

/**
 * One column discovered from a PostgreSQL source's schema, described by
 * metadata only.
 *
 * <p>Exactly four facts, and nothing else: the column name, its
 * {@code ORDINAL_POSITION} as reported by the driver, and its declared data
 * type name. There is deliberately no default value, no length, no
 * {@code CHECK} constraint, no comment, and — above all — no cell value: this
 * type has nowhere to put row data, which is what makes it safe to move through
 * the application. Nothing here is a row, a sample, or a query result.
 *
 * <p>{@code dataType} is the driver's own {@code TYPE_NAME} text, passed
 * through unchanged and never mapped, normalized, or guessed at, so a later
 * milestone can decide how (or whether) a type maps to a transformation.
 *
 * @param name column name as the source reports it, never blank
 * @param ordinalPosition 1-based position within the table, always at least 1
 * @param dataType declared data type name, never blank
 */
public record PostgresColumn(String name, int ordinalPosition, String dataType) {

    public PostgresColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (ordinalPosition < 1) {
            throw new IllegalArgumentException("ordinalPosition must be at least 1");
        }
        if (dataType == null || dataType.isBlank()) {
            throw new IllegalArgumentException("dataType must not be blank");
        }
    }
}
