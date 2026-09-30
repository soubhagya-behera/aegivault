package com.aegivault.aegivault.dataset.postgres;

import java.util.function.Consumer;

/**
 * The narrow, internal boundary for reading actual rows out of one configured
 * PostgreSQL source — the step above
 * {@link PostgresSchemaDiscoveryService} in the same shape of pipeline:
 *
 * <pre>
 *   PostgresSourceProperties
 *       -&gt; PostgresDataSource            (the connection boundary)
 *       -&gt; PostgresSchemaDiscoveryService (metadata only)
 *       -&gt; PostgresTableRowSource        (bounded row stream, this interface)
 * </pre>
 *
 * <p><strong>One operation, and it is a stream.</strong>
 * {@link #streamRows(PostgresDataSource, PostgresTable, Consumer)} delivers rows
 * one at a time to a caller-supplied {@link Consumer} and returns only counts.
 * The caller is handed a {@link PostgresTableRow} at a time and never a
 * collection: there is no {@code List} of rows to accumulate, no
 * {@code queryForList}, no in-memory CSV, and no cache, so peak memory is a
 * function of {@link PostgresRowLimits#fetchSize()} and of the widest single
 * row rather than of the table's size. Nothing is retained after the call
 * returns.
 *
 * <p><strong>No arbitrary SQL, and no way to ask for one.</strong> The table
 * arrives as a {@link PostgresTable} — the discovery record, not a string — so
 * the only thing a caller can select is a table discovery already described, and
 * the columns are the discovered ones. There is no {@code execute}, no
 * {@code query}, no SQL string parameter, no {@code WHERE}, no {@code ORDER BY},
 * no join, no aggregate, no limit clause, and no pagination anywhere on this
 * interface. A caller cannot write a query because a caller cannot reach a
 * statement.
 *
 * <p><strong>JDBC types never escape.</strong> The interface neither returns
 * nor accepts a {@link java.sql.Connection}, {@link java.sql.Statement},
 * {@link java.sql.PreparedStatement}, or {@link java.sql.ResultSet}. Resource
 * ownership sits entirely inside the implementation: the connection, statement,
 * and result set are opened and closed within the one call, including when row
 * processing throws or the row ceiling is reached, so a caller can never leak a
 * source connection and never has to remember to close anything.
 *
 * <p><strong>Read-only, through the existing boundary.</strong> Connections
 * come from {@link PostgresDataSource#openReadOnlyConnection()}, the same
 * read-only session discovery uses, and are closed by the end of the call.
 * Nothing is pooled and no connection outlives its stream.
 *
 * <p><strong>Data handling is the caller's job, under a stated boundary.</strong>
 * This interface is where real source values legitimately appear internally, so
 * the constraints are explicit: nothing here logs a value, embeds one in an
 * exception, persists one, puts one in an audit event, or returns one over
 * HTTP. Values exist only as arguments to the caller's consumer, for the duration
 * of the call. What a value <em>means</em> — whether it is PII, and what should
 * be done to it — is deliberately out of scope here: no detection, no
 * sanitization, and no PII type exists at this layer.
 *
 * @see JdbcPostgresTableRowSource the only implementation
 */
public interface PostgresTableRowSource {

    /**
     * Streams rows of one discovered base table to a consumer, one row at a time.
     *
     * <p>Rows are delivered in the database's own result order, which is
     * <em>not</em> guaranteed to be any particular order: no {@code ORDER BY} is
     * issued, so a caller that needs a defined order must not assume one. Column
     * order within each row is the discovered {@code ORDINAL_POSITION} order.
     *
     * <p>The consumer is invoked at most {@link PostgresRowLimits#maxRows()}
     * times. If the ceiling stops the stream, the returned result says so; the
     * consumer is not told to expect a specific count, and is never asked to
     * fetch the remainder.
     *
     * <p>A consumer that throws propagates unchanged, after the result set,
     * statement, and connection have all been closed — so a caller's own failure
     * cannot leave a source connection open.
     *
     * @param source   configured source to read from, never null; its
     *                 {@link PostgresDataSource#schemaName()} is the one schema
     *                 read
     * @param table    the discovered table to read, never null, with at least one
     *                 discovered column; not a table name or any SQL text
     * @param rowConsumer receives each row in turn, never null, never called
     *                    after the row ceiling
     * @return structural counts and whether the row ceiling was reached, never
     *         null; carries no row value
     * @throws IllegalArgumentException when the source's schema name, the table
     *         name, or any discovered column name is not a plain identifier, or
     *         the table has no columns; raised before any SQL is built and
     *         before the source is contacted
     * @throws PostgresSourceConnectionException when the source could not be
     *         reached
     * @throws PostgresTableRowReadException when the source was reached but its
     *         rows could not be read, including when the table does not exist;
     *         the message is fixed and carries no SQL, identifier, credential,
     *         driver text, or row value
     */
    PostgresRowStreamResult streamRows(
            PostgresDataSource source, PostgresTable table, Consumer<PostgresTableRow> rowConsumer);
}
