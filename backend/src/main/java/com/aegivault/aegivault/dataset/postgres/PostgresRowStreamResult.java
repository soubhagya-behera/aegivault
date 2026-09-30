package com.aegivault.aegivault.dataset.postgres;

/**
 * How one PostgreSQL row stream ended: structural counts only.
 *
 * <p><strong>Truncation is reported, never silent.</strong> A stream that stops
 * because it reached {@link PostgresRowLimits#maxRows()} sets
 * {@link #rowLimitReached()}. That flag is the whole point of this record: a
 * caller that must not treat a bounded read as a complete one — a profiler, a
 * count, a checksum — has something to check, instead of silently processing a
 * prefix of a larger table and reporting it as the table.
 *
 * <p><strong>It carries no data.</strong> Column and row counts are numbers, not
 * values, so this record can be logged, aggregated, or asserted on without
 * exposing a cell. There is deliberately no schema name, no table name, and no
 * position or timestamp here either: the result of a read of production data
 * should not itself be a description of the source.
 *
 * @param columnsRead     number of columns each streamed row carried
 * @param rowsRead        number of rows actually delivered to the caller
 * @param rowLimitReached whether the stream stopped at the configured row
 *                        ceiling, meaning more rows may exist in the source
 */
public record PostgresRowStreamResult(int columnsRead, long rowsRead, boolean rowLimitReached) {

    public PostgresRowStreamResult {
        if (columnsRead < 1) {
            throw new IllegalArgumentException("columnsRead must be at least 1");
        }
        if (rowsRead < 0) {
            throw new IllegalArgumentException("rowsRead must not be negative");
        }
    }

    /**
     * Whether the delivered rows are the whole table. Only true when the stream
     * ended because the source ran out of rows.
     *
     * @return true when the row ceiling stopped the stream
     */
    public boolean truncated() {
        return rowLimitReached;
    }
}
