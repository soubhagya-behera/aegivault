package com.aegivault.aegivault.dataset.postgres;

import java.util.List;
import java.util.Objects;

/**
 * One base table discovered from a PostgreSQL source's schema, described by
 * metadata only.
 *
 * <p>Columns are held in {@code ORDINAL_POSITION} order, because ordinal
 * position is a real fact about the source and a caller that later needs to map
 * a positional format onto this table must be able to rely on it. The order is
 * established once, when the table is built, and the list is immutable.
 *
 * <p>Only the table's name and its columns are carried. The row count is
 * deliberately not discovered: counting rows means reading the table, and this
 * milestone reads metadata only. No view, materialized view, foreign table, or
 * sequence is represented here — they are not tables for this purpose.
 *
 * @param name table name as the source reports it, never blank
 * @param columns columns in ordinal order, never null, possibly empty
 */
public record PostgresTable(String name, List<PostgresColumn> columns) {

    public PostgresTable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        Objects.requireNonNull(columns, "columns must not be null");
        columns = List.copyOf(columns);
    }
}
