package com.aegivault.aegivault.dataset.postgres.discovery;

/**
 * Thrown when the configured source could not be reached or its schema metadata
 * could not be read.
 *
 * <p><strong>One answer for every reason.</strong> "Unreachable" and "metadata
 * unreadable" are deliberately indistinguishable, because separating them would
 * make this endpoint a probe for the source's internals. The message is fixed
 * and carries no SQL, SQL state, driver text, JDBC URL, host, port, database, or
 * credential; the underlying driver exception is not retained here at all, so it
 * cannot leak through a stack trace or a log line.
 *
 * <p>Rendered as a safe 503, the same status as a source that is not configured,
 * so the two cases are also indistinguishable to a caller.
 */
public class PostgresSchemaDiscoveryUnavailableException extends RuntimeException {

    /** The only safe discovery-unavailable message. */
    public static final String MESSAGE = "PostgreSQL source is not available.";

    public PostgresSchemaDiscoveryUnavailableException() {
        super(MESSAGE);
    }
}