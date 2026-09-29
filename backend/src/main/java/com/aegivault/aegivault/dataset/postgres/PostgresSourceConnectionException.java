package com.aegivault.aegivault.dataset.postgres;

/**
 * Signals that a configured PostgreSQL source could not be connected to for
 * read-only discovery. The request was therefore neither answered nor refused —
 * nothing was known.
 *
 * <p><strong>Fail closed, and leak nothing.</strong> The message is fixed and
 * safe: it names no host, port, database, username, JDBC URL, password, secret,
 * SQL state, driver class, or underlying exception text. The cause is retained
 * for server logs only, and is deliberately not surfaced anywhere a caller can
 * read it — a JDBC cause routinely contains the full connection URL, and that
 * string can carry credentials.
 *
 * <p>It is deliberately distinct from {@link PostgresSchemaDiscoveryException},
 * which reports the same class of failure one step later: a caller must be able
 * to tell "could not reach the source" from "reached it and could not read its
 * metadata", and collapsing them would hide which half failed.
 *
 * <p>No HTTP status is chosen here. Nothing exposes this exception yet: this
 * milestone adds no endpoint, so the reusable service is called in-process
 * only.
 */
public class PostgresSourceConnectionException extends RuntimeException {

    /** The only safe connection-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to connect to the PostgreSQL source.";

    public PostgresSourceConnectionException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
