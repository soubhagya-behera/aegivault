package com.aegivault.aegivault.dataset.postgres;

/**
 * Signals that rows of one PostgreSQL source table could not be read. The source
 * was reached and the read did not complete.
 *
 * <p><strong>One message, deliberately.</strong> A missing table, a table whose
 * columns cannot be quoted, a dropped connection, and a permission failure all
 * arrive here with the same fixed, safe text. A caller therefore learns that the
 * read failed and nothing else: not the SQL, not the JDBC URL, not the schema or
 * table name, not the username or password, not the SQL state, and not the
 * driver's own message — which for a PostgreSQL error routinely restates the
 * relation name, the column list, and the statement.
 *
 * <p><strong>No row value is ever in here.</strong> This milestone reads real
 * production data, so the boundary is stated rather than assumed: nothing logs a
 * value, no value is placed in an exception or its message, nothing persists a
 * value, and no audit event carries one. A cell can only leave this package by
 * being handed to the caller that asked for the stream, and the message here is
 * a fixed string that no input can influence.
 *
 * <p><strong>Indistinguishable on purpose.</strong> A table that does not exist
 * is reported exactly like a table that exists but could not be read, so this
 * exception is not an existence oracle for the source. When source ownership is
 * introduced later, an absent table and another owner's table must continue to
 * produce the same result, and nothing in this class makes that harder.
 *
 * <p>The cause is retained for server-side diagnostics only. It is never
 * surfaced to a caller, because a JDBC cause can contain the connection URL and
 * the SQL text.
 */
public class PostgresTableRowReadException extends RuntimeException {

    /** The only safe row-read-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to read PostgreSQL source table.";

    public PostgresTableRowReadException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
