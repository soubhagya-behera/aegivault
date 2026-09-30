package com.aegivault.aegivault.dataset.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The only {@link PostgresTableRowSource}: streams rows of one discovered table
 * over the existing read-only {@link PostgresDataSource}.
 *
 * <p><strong>The statement is built from three validated inputs and nothing
 * else.</strong> The text is exactly
 *
 * <pre>SELECT &lt;column&gt;, &lt;column&gt;, ... FROM &lt;schema&gt;.&lt;table&gt;</pre>
 *
 * where the schema name comes from the source's configuration, and the table and
 * column names come from a {@link PostgresTable} that discovery already read out
 * of the catalog. Each identifier is checked against the strict
 * {@link PostgresIdentifier} grammar — a plain unquoted identifier, no quotes,
 * semicolons, whitespace, dots, comments, or wildcards — and only then wrapped in
 * PostgreSQL double quotes. There is no value binding anywhere, because there
 * are no values in the statement: filtering is not supported, so there is
 * nothing to bind. The literals {@code WHERE}, {@code ORDER BY}, {@code LIMIT},
 * {@code JOIN}, and {@code ;} cannot appear, and no caller input can reach the
 * text at all — the only inputs are a configured schema name and catalog
 * metadata.
 *
 * <p><strong>The grammar is checked before the source is contacted.</strong>
 * Validation runs on the schema name, the table name, and every column name
 * before a connection is opened, so a hostile or unusable name costs nothing and
 * cannot even be used to probe which tables exist. A table whose column names
 * are not plain identifiers is refused whole rather than partly read.
 *
 * <p><strong>Memory is bounded by the call, not by the table.</strong> The
 * result set is consumed with {@code next()} in a loop and each row is handed
 * straight to the consumer and dropped; nothing accumulates, nothing is cached,
 * and no collection of rows exists at any point. The statement asks the driver
 * for {@link PostgresRowLimits#fetchSize()} rows at a time, so a table is not
 * transferred into client memory wholesale. The loop stops at
 * {@link PostgresRowLimits#maxRows()}, which is why a single invocation cannot
 * run indefinitely over an enormous table.
 *
 * <p><strong>The caller's exception wins.</strong> If the consumer throws, that
 * exception propagates unchanged rather than being wrapped, so a caller's own
 * failure is not disguised as a source failure; the JDBC resources are closed
 * first on the way out.
 *
 * <p><strong>Everything opened here is closed here.</strong> The result set and
 * statement are closed by try-with-resources and the connection in a
 * {@code finally}, so all three are closed on the normal path, when the row
 * ceiling is reached, when the consumer throws, and when the driver itself
 * throws. The caller closes nothing, because the caller is given nothing to
 * close. One call uses one connection and never holds it beyond the call —
 * there is no pool, and no connection survives the method.
 *
 * <p><strong>Read-only, through the existing boundary.</strong> The connection
 * comes from {@link PostgresDataSource}, the same abstraction discovery uses, so
 * this class never touches {@code DriverManager}, a JDBC URL, or a credential,
 * and cannot weaken the read-only session guarantee that boundary already
 * provides.
 *
 * <p><strong>Failure is one fixed message.</strong> Every {@link SQLException}
 * — a missing table, a dropped connection, a permission failure — leaves as
 * {@link PostgresTableRowReadException} with the same safe text. A missing table
 * is reported exactly like any other failure, so this class is not an existence
 * oracle for the source, and no SQL, identifier, credential, driver text, or row
 * value is ever placed in a message.
 *
 * <p><strong>Dependency direction is one-way and narrow.</strong> This class
 * depends on {@link PostgresDataSource} and the JDK, and on nothing else in
 * Aegivault — no dataset persistence, no sanitization, no gateway, no policy
 * enforcement, no audit ledger, no PII detection, no Redis, and no controller.
 * It is wired as a bean only when a source is configured, by
 * {@link PostgresSourceConfiguration}.
 */
public class JdbcPostgresTableRowSource implements PostgresTableRowSource {

    private final PostgresRowLimits limits;

    /**
     * @param limits row ceiling and fetch size, never null
     * @throws NullPointerException when {@code limits} is null
     */
    public JdbcPostgresTableRowSource(PostgresRowLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    @Override
    public PostgresRowStreamResult streamRows(
            PostgresDataSource source, PostgresTable table, Consumer<PostgresTableRow> rowConsumer) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (rowConsumer == null) {
            throw new NullPointerException("rowConsumer must not be null");
        }
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }
        // Every identifier is validated and the whole statement is built BEFORE
        // the source is contacted: an unusable name costs nothing, cannot be used
        // to probe what exists, and cannot fail after a connection was opened.
        String schema = PostgresIdentifier.requireSchemaName(source.schemaName());
        List<PostgresColumn> columns = table.columns();
        String sql = selectStatement(schema, table);
        Connection connection = source.openReadOnlyConnection();
        try {
            return readRows(connection, sql, columns, rowConsumer);
        } finally {
            close(connection);
        }
    }

    /**
     * Validates the table and builds the one statement this class can build:
     * {@code SELECT <columns> FROM <schema>.<table>}.
     *
     * <p>Columns keep the discovered {@code ORDINAL_POSITION} order, so a row's
     * values line up with the metadata the caller already holds. Every part is a
     * quoted, validated identifier; there is no literal, no binding, no clause
     * beyond {@code SELECT} and {@code FROM}, and no caller-supplied text.
     *
     * <p>The table name is checked as an identifier in its own right before it
     * is combined with the schema, so a name carrying a dot cannot smuggle a
     * second relation past the schema that was validated. A table with a column
     * name that cannot be quoted is refused whole rather than partly read, since
     * a partly selectable table would be a silently incomplete read.
     */
    private static String selectStatement(String schema, PostgresTable table) {
        if (table.columns().isEmpty()) {
            throw new IllegalArgumentException("a streamable table must have at least one column");
        }
        StringBuilder select = new StringBuilder("SELECT ");
        for (int index = 0; index < table.columns().size(); index++) {
            if (index > 0) {
                select.append(", ");
            }
            select.append(PostgresIdentifier.quotedIdentifier(table.columns().get(index).name()));
        }
        return select.append(" FROM ")
                .append(PostgresIdentifier.quotedIdentifier(schema))
                .append('.')
                .append(PostgresIdentifier.quotedIdentifier(table.name()))
                .toString();
    }

    private PostgresRowStreamResult readRows(Connection connection, String sql,
            List<PostgresColumn> columns, Consumer<PostgresTableRow> rowConsumer) {
        try {
            streamRatherThanDownload(connection);
        } catch (SQLException ex) {
            throw new PostgresTableRowReadException(ex);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // A fetch size is what keeps the driver from materialising the whole
            // result set in client memory before the first row is delivered.
            statement.setFetchSize(limits.fetchSize());
            try (ResultSet rows = statement.executeQuery()) {
                return deliver(rows, columns, rowConsumer);
            }
        } catch (SQLException ex) {
            // The driver's message routinely restates the relation, the columns,
            // and the statement, so only the fixed safe wrapper leaves here.
            throw new PostgresTableRowReadException(ex);
        }
    }

    /**
     * Asks the driver to stream rather than download the whole result set.
     *
     * <p>The fetch size set on the statement is only honoured by the PostgreSQL
     * driver when the connection is <em>not</em> in auto-commit mode; with
     * auto-commit on, the driver fetches the entire result before the first
     * {@code next()} and the fetch size is only a hint about packet size. So
     * auto-commit is turned off for the duration of the read, which is what
     * actually makes the streaming claim true rather than aspirational.
     *
     * <p>This is safe here precisely because nothing is ever written: the
     * connection is the source's read-only session, so the open transaction
     * cannot contain a change, and {@link Connection#close()} ends it. No
     * commit is issued — there is nothing to commit, and asking to commit a
     * read-only transaction would be both meaningless and a write attempt.
     *
     * <p><strong>This is also what makes read-only real rather than
     * nominal.</strong> PostgreSQL enforces a read-only session
     * <em>per transaction</em>: in auto-commit mode every statement is its own
     * transaction, so a write there is not actually refused. Wrapping the read
     * in one explicit transaction is what makes the server reject a write on
     * this connection, so the same setting that makes the fetch size honest is
     * the one that makes the read-only guarantee hold.
     *
     * <p>A connection that arrived with auto-commit already off is left off, so
     * an implementation that hands out a transactional connection keeps it that
     * way rather than silently committing somebody else's transaction.
     */
    private static void streamRatherThanDownload(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
        }
    }

    /**
     * Hands rows to the consumer one at a time, stopping at the row ceiling.
     *
     * <p>The ceiling is checked before the next row is handed over rather than
     * after, so a table larger than the bound is never walked past it, and the
     * fact that the bound stopped the stream is reported in the result rather
     * than passed off as the end of the table.
     */
    private PostgresRowStreamResult deliver(
            ResultSet rows, List<PostgresColumn> columns, Consumer<PostgresTableRow> rowConsumer)
            throws SQLException {
        long delivered = 0;
        while (rows.next()) {
            if (delivered >= limits.maxRows()) {
                return new PostgresRowStreamResult(columns.size(), delivered, true);
            }
            rowConsumer.accept(new PostgresTableRow(columns, readValues(rows, columns.size())));
            delivered++;
        }
        return new PostgresRowStreamResult(columns.size(), delivered, false);
    }

    /**
     * Reads one row's values by position.
     *
     * <p>{@code getObject} is used so each value keeps the driver's own JDBC
     * type and a SQL NULL stays {@code null}: nothing is coerced to text,
     * parsed, interpreted, or otherwise inspected, and no domain type is created
     * from a value here.
     */
    private static List<Object> readValues(ResultSet rows, int columnCount) throws SQLException {
        List<Object> values = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            values.add(rows.getObject(index));
        }
        return values;
    }

    /** Closes the caller's connection, never masking a read failure. */
    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A failure to close does not change what was read, and there is
            // nothing safe to report about it.
        }
    }
}
