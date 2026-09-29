package com.aegivault.aegivault.dataset.postgres;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection metadata for one configured PostgreSQL source, under
 * {@code aegivault.dataset.postgres}.
 *
 * <p><strong>Configuration only — nothing is persisted.</strong> There is no
 * source table, no repository, and no CRUD API: the source is supplied as
 * service configuration, and persisting source definitions is deliberately a
 * later milestone, once this boundary has been proven. A source configured here
 * exists for the lifetime of the process and nowhere else.
 *
 * <p><strong>One explicit schema.</strong> {@code schema} names exactly the
 * schema discovery is allowed to inspect, defaulting to {@code public}. There
 * is no cross-schema browsing and no wildcard.
 *
 * <p><strong>The password lives only here.</strong> It is bound from
 * configuration (the local, git-ignored properties file or the environment) and
 * is never written to the database, echoed into a message, logged, or included
 * in a result. The committed example file carries only a placeholder.
 *
 * <p><strong>Timeouts are bounded.</strong> {@code connect-timeout} defaults to
 * 5 seconds and is rejected when it is not positive or exceeds 60 seconds, so a
 * misconfiguration fails at startup instead of letting a connection attempt
 * hang for an unbounded time.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.dataset.postgres")
public class PostgresSourceProperties {

    /** Largest acceptable connection timeout, in seconds. */
    public static final int MAX_CONNECT_TIMEOUT_SECONDS = 60;

    private String host = "localhost";

    private int port = 5432;

    private String database = "";

    private String username = "";

    private String password = "";

    private String schema = "public";

    private int connectTimeoutSeconds = 5;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }
}
