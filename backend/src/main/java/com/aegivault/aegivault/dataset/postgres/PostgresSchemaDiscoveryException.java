package com.aegivault.aegivault.dataset.postgres;

/**
 * Signals that a connected PostgreSQL source's schema metadata could not be
 * inspected. The connection was established, but the metadata read did not
 * complete.
 *
 * <p><strong>Distinct from a connection failure on purpose.</strong>
 * {@link PostgresSourceConnectionException} means the source was never reached;
 * this means it was reached and its catalog could not be read. An operator
 * diagnosing an incident needs those two facts to stay separate.
 *
 * <p>The message is fixed and safe: no schema name, table name, JDBC URL, host,
 * port, username, password, SQL text, SQL state, or driver text. The cause is
 * retained for server logs only, and never surfaced to a caller.
 */
public class PostgresSchemaDiscoveryException extends RuntimeException {

    /** The only safe discovery-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to inspect the PostgreSQL source schema.";

    public PostgresSchemaDiscoveryException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
