package com.aegivault.aegivault.dataset.postgres.discovery;

/**
 * Thrown when no PostgreSQL source is configured for this deployment, so there
 * is nothing to discover.
 *
 * <p>Rendered as a safe 503. The message is fixed and carries no host, port,
 * database, username, password, or JDBC URL, so an unauthorised caller learns
 * only that the optional integration is absent — and, because ownership is checked
 * before this is raised, only an authorised caller learns even that.
 */
public class PostgresSourceUnavailableException extends RuntimeException {

    /** The only safe source-unavailable message. */
    public static final String MESSAGE = "PostgreSQL source is not available.";

    public PostgresSourceUnavailableException() {
        super(MESSAGE);
    }
}