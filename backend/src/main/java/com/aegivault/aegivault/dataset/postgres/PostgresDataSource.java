package com.aegivault.aegivault.dataset.postgres;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A configured PostgreSQL source, seen only as "the one schema to inspect" and
 * "a way to open a connection to it".
 *
 * <p><strong>The connection boundary, and nothing more.</strong> This interface
 * exists so schema discovery depends on an abstraction rather than on
 * {@code DriverManager}, a URL, or credentials: the discovery layer never sees
 * a host, a port, a password, or a JDBC URL, and cannot leak what it cannot
 * reach. It is the PostgreSQL counterpart of
 * {@link com.aegivault.aegivault.dataset.DatasetInputSource} — a named seam
 * instead of ad-hoc driver access.
 *
 * <p><strong>Contract for implementations.</strong>
 *
 * <ul>
 *   <li>{@link #openReadOnlyConnection()} returns a fresh, caller-owned
 *       connection that is <em>read-only</em> for the session it opens. The
 *       caller closes it; the implementation never closes, caches, pools, or
 *       shares it, and never returns null.</li>
 *   <li>The connection is for metadata inspection only. Implementations must
 *       not read rows and must not execute caller-supplied SQL — this
 *       interface deliberately exposes no way to run a statement.</li>
 *   <li>{@link #schemaName()} names exactly one schema. There is no
 *       cross-schema browsing, no default-schema fallback, and no wildcard.</li>
 *   <li>A failure to connect is reported as
 *       {@link PostgresSourceConnectionException}, which carries a fixed safe
 *       message and no connection detail.</li>
 * </ul>
 *
 * <p><strong>Read-only is enforced in code as far as a client can enforce
 * it.</strong> Implementations set the connection read-only, which PostgreSQL
 * turns into a read-only session, so a write issued on this connection fails
 * server-side. That is a strong client-side guarantee but not a complete one:
 * it does not stop a DBA-privileged account from being <em>able</em> to write,
 * and it does not protect the source from any other connection. Production use
 * therefore also requires a database role that itself cannot write — see the
 * package and deployment notes.
 */
public interface PostgresDataSource {

    /**
     * The single schema this source is configured to expose.
     *
     * @return schema name, never blank
     */
    String schemaName();

    /**
     * Opens one fresh read-only connection to the source.
     *
     * <p>The caller owns the returned connection and must close it, normally
     * with try-with-resources. Attempts are bounded by the configured
     * connection timeout, so a hung source cannot hang the caller indefinitely.
     *
     * @return a fresh caller-owned read-only connection, never null
     * @throws PostgresSourceConnectionException when the source could not be
     *         reached; the message carries no host, port, database, username,
     *         JDBC URL, or password
     */
    Connection openReadOnlyConnection();
}
