package com.aegivault.aegivault.dataset.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The configured {@link PostgresDataSource} that opens its own read-only JDBC
 * connection from {@link PostgresSourceProperties}.
 *
 * <p>This is the only class in the package that sees a JDBC URL, a username, or
 * a password — and it holds them, deliberately, so nothing else can. It never
 * logs, stores, or reports them: {@link #toString()} names the host, port,
 * database, and schema for diagnostics and omits the credentials entirely, and
 * every failure leaves this class as
 * {@link PostgresSourceConnectionException} with a fixed safe message.
 *
 * <p><strong>Bounded connection attempts.</strong> The configured timeout is
 * passed to the driver as {@code loginTimeout} and {@code connectTimeout}, so a
 * silent host cannot hold a caller indefinitely; the value is range-checked at
 * construction (positive, at most
 * {@link PostgresSourceProperties#MAX_CONNECT_TIMEOUT_SECONDS}) so a bad
 * timeout cannot silently disable the bound.
 *
 * <p><strong>Read-only, twice.</strong> {@code readOnly} is requested on the
 * connection properties and re-applied with {@code setReadOnly(true)} once the
 * connection exists, which PostgreSQL turns into a read-only session: a write
 * attempted on this connection fails server-side rather than being trusted to
 * good behaviour. That is a client-side guarantee, and this class documents
 * plainly what it cannot do — see the connection limitation note below.
 *
 * <p><strong>One connection per call, never pooled.</strong> Every
 * {@link #openReadOnlyConnection()} opens and hands over a fresh connection for
 * one discovery call, and the caller closes it. Nothing is cached, kept open
 * across a request, or held for a later sanitization run: pooling and
 * long-lived source connections are explicitly out of scope for this
 * milestone.
 *
 * <p><strong>The limitation, stated honestly.</strong> Read-only is enforced for
 * the session this code opens. It does not change what the configured database
 * account is <em>entitled</em> to do, and it does not protect the source from
 * any other connection. Production use therefore requires a source role that
 * itself cannot write (for example a role granted only {@code CONNECT} and
 * {@code SELECT}, or a role with {@code default_transaction_read_only = on}),
 * configured by whoever operates the database.
 */
public final class DriverManagerPostgresDataSource implements PostgresDataSource {

    private final String host;

    private final int port;

    private final String database;

    private final String username;

    private final String password;

    private final String schema;

    private final int connectTimeoutSeconds;

    /**
     * @param properties configured source metadata, never null
     * @throws IllegalArgumentException when an identifier, port, or timeout is
     *         unusable; the message names the field and never its value
     */
    public DriverManagerPostgresDataSource(PostgresSourceProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        this.host = requireText(properties.getHost(), "host");
        this.port = requirePort(properties.getPort());
        this.database = requireText(properties.getDatabase(), "database");
        this.username = requireText(properties.getUsername(), "username");
        this.password = properties.getPassword() == null ? "" : properties.getPassword();
        this.schema = PostgresIdentifier.requireSchemaName(properties.getSchema());
        this.connectTimeoutSeconds = requireTimeout(properties.getConnectTimeoutSeconds());
    }

    @Override
    public String schemaName() {
        return schema;
    }

    @Override
    public Connection openReadOnlyConnection() {
        Properties credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        credentials.setProperty("readOnly", "true");
        // Both bounds are honoured by the PostgreSQL driver: loginTimeout
        // bounds establishing the socket, connectTimeout the handshake.
        credentials.setProperty("loginTimeout", String.valueOf(connectTimeoutSeconds));
        credentials.setProperty("connectTimeout", String.valueOf(connectTimeoutSeconds));
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl(), credentials);
            // Ask the server for a read-only session rather than trusting the
            // connection property alone: PostgreSQL rejects writes for it.
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException ex) {
            // The driver's own message routinely contains the full URL,
            // including credentials, so only the safe wrapper ever leaves here.
            throw new PostgresSourceConnectionException(ex);
        }
    }

    /**
     * The JDBC URL for the configured source.
     *
     * <p>Deliberately private: a URL is a credential-bearing string, so it is
     * built at the point of use and never returned from a public method, stored
     * in a field a caller can read, or placed in a message.
     */
    private String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    /**
     * Diagnostic identity only: host, port, database, schema, and timeout. The
     * username and password are deliberately absent, so an accidental log line
     * or test failure message cannot carry a secret.
     */
    @Override
    public String toString() {
        return "DriverManagerPostgresDataSource[host=" + host + ", port=" + port
                + ", database=" + database + ", schema=" + schema
                + ", connectTimeoutSeconds=" + connectTimeoutSeconds + "]";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static int requirePort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }

    private static int requireTimeout(int seconds) {
        if (seconds < 1 || seconds > PostgresSourceProperties.MAX_CONNECT_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("connectTimeoutSeconds must be between 1 and "
                    + PostgresSourceProperties.MAX_CONNECT_TIMEOUT_SECONDS);
        }
        return seconds;
    }
}
