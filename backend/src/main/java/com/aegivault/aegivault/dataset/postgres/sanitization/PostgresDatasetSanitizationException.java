package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.sanitization.SanitizationSourceException;

/**
 * Thrown when a bound PostgreSQL dataset cannot be sanitized: no source is
 * configured, the bound table is no longer a discovered base table (a stale
 * binding), discovery failed, or the row stream could not be read.
 *
 * <p><strong>One message for every reason, on purpose.</strong> "That table is
 * gone" and "the source is unreachable" are deliberately indistinguishable to the
 * caller. Separating them would make sanitization a metadata oracle for the
 * database — telling an owner whether a name still exists in a schema — and no
 * such capability is intended.
 *
 * <p><strong>Nothing about the source escapes.</strong> The message is fixed and
 * carries no JDBC URL, host, port, database, username, password, SQL text, SQL
 * state, driver text, schema name, table name, or row value. A driver exception
 * routinely contains all of those, so it is retained only as the cause for
 * server-side logs.
 *
 * <p>Extends {@link SanitizationSourceException} so the existing run executor
 * translates it into a {@code FAILED} run with the source stage, rather than
 * inventing a run outcome here.
 */
public class PostgresDatasetSanitizationException extends SanitizationSourceException {

    /** The only safe PostgreSQL sanitization-failure message. */
    public static final String MESSAGE = "The bound PostgreSQL table could not be sanitized.";

    public PostgresDatasetSanitizationException(Throwable cause) {
        super(cause);
    }

    /**
     * Narrows the inherited generic wording to this source. The value is a fixed
     * constant, never built from the cause, so it cannot leak a source detail.
     *
     * @return the fixed safe message, always the same for every reason
     */
    @Override
    public String getMessage() {
        return MESSAGE;
    }
}