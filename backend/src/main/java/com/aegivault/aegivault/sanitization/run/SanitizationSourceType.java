package com.aegivault.aegivault.sanitization.run;

/**
 * Which source a {@link SanitizationRun} reads its rows from.
 *
 * <p><strong>Explicit, never inferred.</strong> The value is written once, by the
 * creation path that actually knows what the caller asked for, and read once, by
 * the dispatch that must choose an execution path. Nothing derives it from an
 * unrelated field — not from whether a dataset happens to have stored input, not
 * from whether a PostgreSQL binding exists — because guessing would make the same
 * queued run mean different things depending on data that can change between
 * queueing and execution.
 *
 * <p><strong>It identifies a source kind, never a location.</strong> There is no
 * host, port, database, username, password, JDBC URL, schema, or table name
 * here, and no room for SQL. A PostgreSQL run is located through its dataset's
 * binding and the application's configured {@code PostgresDataSource}, which
 * remains the only place credentials live.
 *
 * <p>{@link #CSV} is the historical behaviour and the migration default, so every
 * run that existed before PostgreSQL support is a CSV run and continues down the
 * existing unchanged path.
 */
public enum SanitizationSourceType {

    /** Rows come from the dataset's persisted CSV input. The existing path. */
    CSV,

    /** Rows come from the PostgreSQL base table the dataset is bound to. */
    POSTGRESQL
}