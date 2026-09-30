package com.aegivault.aegivault.dataset.postgres;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One row of one discovered PostgreSQL base table: the discovered column
 * metadata it was read through, and that row's values in the same order.
 *
 * <p><strong>A carrier, not an interpretation.</strong> A value is whatever the
 * JDBC driver returned for that cell — a {@link String}, an {@link Integer}, a
 * {@link Boolean}, a {@link java.math.BigDecimal}, a {@link java.time.LocalDate},
 * an {@link java.util.UUID}, a {@code byte[]}, or a driver-specific type such as
 * the one PostgreSQL uses for {@code jsonb} — and {@code null} for SQL NULL.
 * Nothing here detects, classifies, redacts, or reformats a value: there is no
 * PII type, no confidence, no transformation, and no policy. Deciding what a
 * value <em>is</em> is a later milestone's job, and this record is deliberately
 * shaped so it cannot be mistaken for the result of that work.
 *
 * <p><strong>Immutability, including the null case.</strong> The column and
 * value lists are copied and wrapped unmodifiable on construction, so a caller
 * cannot alter a row it has been handed, and the caller's own list cannot be
 * mutated underneath it afterwards. The copy is built with
 * {@link Collections#unmodifiableList(List)} rather than
 * {@link List#copyOf(List)} on purpose: {@code copyOf} rejects {@code null}
 * elements, and a SQL NULL is a real, meaningful value that this record must be
 * able to carry.
 *
 * <p><strong>Nothing prints a value.</strong> {@link #toString()} is overridden
 * to report the shape of the row only — column count, value count, and column
 * names, all of which are metadata. A row cannot therefore leak a cell into a log
 * line, a test failure message, or an exception through an accidental
 * concatenation or an IDE's value inspector calling {@code toString()}.
 *
 * <p>Column order is the discovered {@code ORDINAL_POSITION} order, so index
 * {@code i} of {@link #values()} is the value of {@link #columns()}{@code
 * [i]}, and {@link #column(int)} gives the matching metadata.
 *
 * @param columns discovered column metadata in ordinal order, never null, never
 *        empty
 * @param values this row's values in the same order, never null, exactly as
 *        wide as {@code columns}; elements may be {@code null}
 */
public record PostgresTableRow(List<PostgresColumn> columns, List<Object> values) {

    public PostgresTableRow {
        Objects.requireNonNull(columns, "columns must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        if (values.size() != columns.size()) {
            throw new IllegalArgumentException("values must be as wide as columns");
        }
        columns = Collections.unmodifiableList(new ArrayList<>(columns));
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * Number of columns in this row, always the discovered table's column count.
     *
     * @return the number of values this row carries
     */
    public int columnCount() {
        return values.size();
    }

    /**
     * The discovered metadata of the column at one position.
     *
     * @param position 0-based position within the row
     * @return the column metadata, never null
     * @throws IndexOutOfBoundsException when the position is not a column of
     *         this row
     */
    public PostgresColumn column(int position) {
        if (position < 0 || position >= columns.size()) {
            throw new IndexOutOfBoundsException("position is not a column of this row");
        }
        return columns.get(position);
    }

    /**
     * The value at one position.
     *
     * @param position 0-based position within the row
     * @return the value, or {@code null} when the source column is SQL NULL
     * @throws IndexOutOfBoundsException when the position is not a column of
     *         this row
     */
    public Object valueAt(int position) {
        if (position < 0 || position >= values.size()) {
            throw new IndexOutOfBoundsException("position is not a column of this row");
        }
        return values.get(position);
    }

    /**
     * The value of one column, looked up by its discovered name.
     *
     * @param columnName column name as discovery reported it, never null or blank
     * @return the value, or {@code null} when the source column is SQL NULL
     * @throws IllegalArgumentException when this row has no such column
     */
    public Object value(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(columnName)) {
                return values.get(index);
            }
        }
        throw new IllegalArgumentException("this row has no such column");
    }

    /**
     * Shape only. Column names and counts are metadata; no value is ever
     * rendered here, so a row cannot leak a cell into a log or a message.
     *
     * @return a value-free description of this row
     */
    @Override
    public String toString() {
        return "PostgresTableRow[columnCount=" + columnCount() + ", columns=" + columns.stream()
                .map(PostgresColumn::name).toList() + ", values=withheld]";
    }
}
