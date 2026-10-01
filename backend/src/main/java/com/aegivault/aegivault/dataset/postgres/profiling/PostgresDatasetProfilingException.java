package com.aegivault.aegivault.dataset.postgres.profiling;

/**
 * Thrown when PostgreSQL dataset profiling cannot be completed: the source is
 * not configured, the bound table can no longer be discovered as a base table
 * (a stale binding), discovery failed, or the PII analysis over the rows failed.
 *
 * <p><strong>One message for every reason, on purpose.</strong> "That table is
 * gone" and "the source is unreachable" are deliberately indistinguishable to the
 * caller. Distinguishing them would turn profiling into a metadata oracle for the
 * database — telling an owner whether a name still exists in a schema they are
 * otherwise allowed to profile — and no such capability is intended.
 *
 * <p><strong>Nothing about the source escapes.</strong> The message is fixed and
 * carries no JDBC URL, host, port, database, username, password, SQL text, SQL
 * state, driver text, schema name, table name, or row value. A driver exception
 * routinely carries all of those, so it is retained only as the cause for
 * server-side logs and is never used to build this message.
 *
 * <p>A missing or foreign binding is <em>not</em> reported through this
 * exception: that is the binding layer's own
 * {@code PostgresDatasetBindingNotFoundException}, which says nothing about the
 * source at all.
 */
public class PostgresDatasetProfilingException extends RuntimeException {

    /** The only safe profiling-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to profile the bound PostgreSQL source table.";

    public PostgresDatasetProfilingException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public PostgresDatasetProfilingException() {
        super(MESSAGE);
    }
}