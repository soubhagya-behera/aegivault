package com.aegivault.aegivault.dataset.postgres;

/**
 * The two bounds on one PostgreSQL row stream: how many rows one call may
 * deliver, and how many the driver may hold at a time.
 *
 * <p><strong>The row ceiling is the safety property.</strong> Without it, a
 * single call against a table with a billion rows would keep reading for as long
 * as the source kept answering. The ceiling is enforced <em>while</em> reading
 * rather than by a {@code LIMIT} clause, which is deliberate: it keeps the
 * statement free of caller influence, and it means the bound applies to rows
 * actually delivered to the caller rather than rows the server chose to scan.
 *
 * <p><strong>The fetch size is a memory hint, not a limit.</strong> It is the
 * number of rows the PostgreSQL driver is asked to hold while the stream is being
 * consumed, so a small table is not pulled into client memory in one gulp. It
 * bounds the working set of an in-progress read and nothing else — it never
 * limits how many rows the stream delivers, and it is not a defence against a
 * source that sends arbitrarily large individual values.
 *
 * <p><strong>Defaults are conservative development values.</strong> 1,000 rows
 * and a 100-row fetch are chosen to be obviously small rather than to be tuned.
 * Both ceilings are range-checked at construction, so a misconfiguration fails
 * loudly at startup instead of quietly removing a bound. They are not a claim of
 * complete denial-of-service protection: they bound the work and the memory of
 * one call, nothing more.
 *
 * @param maxRows    maximum rows delivered by one stream, at least 1
 * @param fetchSize  maximum rows the driver holds at a time, at least 1 and at
 *                   most {@link #MAX_FETCH_SIZE}
 */
public record PostgresRowLimits(int maxRows, int fetchSize) {

    /** Conservative default: one call never walks a large table. */
    public static final int DEFAULT_MAX_ROWS = 1_000;

    /** Largest acceptable row ceiling. */
    public static final int MAX_MAX_ROWS = 1_000_000;

    /** Conservative default fetch size for the PostgreSQL driver. */
    public static final int DEFAULT_FETCH_SIZE = 100;

    /**
     * Largest acceptable fetch size. Above this the driver's own
     * {@code max_fetch_size} ceiling applies, so a larger request would be
     * silently clamped rather than honoured.
     */
    public static final int MAX_FETCH_SIZE = 10_000;

    public PostgresRowLimits {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be at least 1");
        }
        if (maxRows > MAX_MAX_ROWS) {
            throw new IllegalArgumentException("maxRows must be at most " + MAX_MAX_ROWS);
        }
        if (fetchSize < 1) {
            throw new IllegalArgumentException("fetchSize must be at least 1");
        }
        if (fetchSize > MAX_FETCH_SIZE) {
            throw new IllegalArgumentException("fetchSize must be at most " + MAX_FETCH_SIZE);
        }
    }

    /**
     * Default limits for local development.
     *
     * @return a fresh instance of the default limits
     */
    public static PostgresRowLimits defaults() {
        return new PostgresRowLimits(DEFAULT_MAX_ROWS, DEFAULT_FETCH_SIZE);
    }
}
