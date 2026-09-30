package com.aegivault.aegivault.dataset.postgres.binding;

/**
 * Thrown when the requested schema/table cannot be confirmed as a discovered
 * base table of the configured PostgreSQL source — the source is not configured
 * at all, or the table is not a base table it can see.
 *
 * <p><strong>One message for every reason, on purpose.</strong> "No source
 * configured" and "that table does not exist" are deliberately
 * indistinguishable to a caller: confirming which schemas or tables exist in a
 * source would turn binding into a metadata oracle for the database, and no such
 * capability is intended. The failure also does not expose JDBC internals — no
 * SQL, connection URL, host, port, username, password, or driver text — because
 * those routinely reach a caller through a raw driver exception.
 *
 * <p>This is an application-level failure about a configuration/selection, not a
 * source-transport failure: a source that cannot be reached still surfaces as
 * {@code PostgresSourceConnectionException} and a source whose metadata cannot
 * be read as {@code PostgresSchemaDiscoveryException}, so each layer keeps its
 * own signal.
 */
public class PostgresDatasetBindingSourceException extends RuntimeException {

    /** The only safe source-verification message, shared by every throw site. */
    public static final String MESSAGE =
            "The PostgreSQL source table could not be verified.";

    public PostgresDatasetBindingSourceException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
