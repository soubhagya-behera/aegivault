package com.aegivault.aegivault.dataset.postgres;

/**
 * Signals that the PII profiling stage of a PostgreSQL table profile failed.
 * The source was reached and its rows were readable, but the profile could not
 * be computed.
 *
 * <p><strong>Distinct on purpose, so a caller can tell the halves apart.</strong>
 * A malformed configuration still fails fast as an
 * {@link IllegalArgumentException}, a source that cannot be reached still
 * fails as {@link PostgresSourceConnectionException}, a schema that cannot be
 * read still fails as {@link PostgresSchemaDiscoveryException}, and rows that
 * cannot be read still fail as {@link PostgresTableRowReadException} — each of
 * those propagates unchanged. This exception covers only the one remaining
 * case: the bounded row stream arrived intact and the PII analysis over it
 * failed.
 *
 * <p><strong>One fixed, safe message.</strong> It names no SQL, no JDBC
 * exception text, no connection URL, no host, port, username, password, no
 * schema or table name, and — the point that matters here — no row value. This
 * milestone puts real database values in memory in order to analyse them, and a
 * detector that fails while inspecting a value can easily build a message that
 * quotes that value back; this wrapper is the boundary that prevents it. The
 * cause is retained for server-side diagnostics only.
 */
public class PostgresTableProfileException extends RuntimeException {

    /** The only safe profiling-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to profile the PostgreSQL source table.";

    public PostgresTableProfileException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
