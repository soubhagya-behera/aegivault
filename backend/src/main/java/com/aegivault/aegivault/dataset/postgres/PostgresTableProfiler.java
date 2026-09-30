package com.aegivault.aegivault.dataset.postgres;

import com.aegivault.aegivault.pii.profile.ColumnInput;
import com.aegivault.aegivault.pii.profile.ColumnProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Profiles one discovered PostgreSQL table with the existing PII engine and
 * returns the existing {@link DatasetProfile} model, in memory only.
 *
 * <p><strong>The pipeline, and nothing else.</strong>
 *
 * <pre>
 *   discovered PostgresTable
 *       -&gt; PostgresTableRowSource   (bounded, read-only, one row at a time)
 *       -&gt; JDBC value -&gt; profiling text (the only conversion, see below)
 *       -&gt; PiiColumnProfiler        (existing detection, counting, rates)
 *       -&gt; DatasetProfile           (existing model, not persisted)
 * </pre>
 *
 * <p><strong>Reuse, not duplication.</strong> Every PII fact is produced by
 * {@link PiiColumnProfiler}: which detectors run, how supplied/analyzed/analyzable
 * counts are derived, how per-type detection counts and rates are computed, and
 * how detected types are ordered. This class contributes exactly one thing the
 * engine did not have — a way to read values out of a database table — and it
 * deliberately does <em>not</em> re-implement detection, counting, rates, or
 * transformation policy. The result is the ordinary
 * {@code com.aegivault.aegivault.pii.profile.DatasetProfile}, so a PostgreSQL
 * profile and a CSV profile are directly comparable and no second,
 * PostgreSQL-specific profile model exists.
 *
 * <p><strong>Column order is the source's ordinal order.</strong> Columns are
 * profiled and returned in discovered {@code ORDINAL_POSITION} order, which is a
 * fact about the table and is stable across runs. This is a deliberate,
 * documented difference from {@code DatasetProfiler}, which orders by column
 * name for CSV-style headers; ordinal order is kept here because a caller
 * mapping a positional format onto this table must be able to rely on it. The
 * distinction is visible rather than silent, and it is the only ordering
 * decision this class makes — detector order and detected-type order are the
 * existing engine's, unchanged.
 *
 * <p><strong>What "profiled" means here, stated honestly.</strong> The profile
 * describes <em>the rows this call actually streamed</em>, which is bounded by
 * the row source's configured row ceiling. {@link DatasetProfile} has no
 * truncation field, and inventing one would mean a new profile schema in a
 * milestone whose scope is reuse — so the bound is stated here instead: a
 * caller that must not mistake a bounded sample for a whole table should read
 * the per-column {@code suppliedValueCount} and compare it with the table's row
 * count. Nothing in the returned model claims more than was seen.
 *
 * <p><strong>Values are converted to text once, here, and never reinterpreted.</strong>
 * The JDBC value from {@link PostgresTableRow} becomes profiling text by
 * {@link #toProfilingText(Object)}: a {@code String} is used as-is, a SQL NULL
 * stays {@code null} (never the four letters {@code "null"}), binary is left
 * un-decoded as {@code null}, and anything else uses its own {@code toString}.
 * There is no tokenization, no language detection, no normalization, no
 * redaction, and no provider-specific transformation: the existing string-oriented
 * detectors are simply shown the value's own text form. Conversion is
 * deterministic, so equal inputs always produce equal profiles.
 *
 * <p><strong>Rows are processed one at a time.</strong> The stream's consumer
 * is invoked per row and each row is dropped immediately; no table snapshot, no
 * CSV string, and no single list of all rows is ever built. Per-column value
 * lists are the one bounded structure, and their length is capped by the row
 * source's own configured ceiling — the same bound that already governs the read.
 *
 * <p><strong>Row values never leave this call.</strong> Nothing logs a value,
 * none is placed in an exception or its message, none is persisted, none goes
 * into an audit event, and none is returned over HTTP. The returned profile
 * holds counts and type names only, exactly as a CSV profile does.
 *
 * <p><strong>Dependency direction is one-way.</strong> This class depends on
 * {@link PostgresTableRowSource} and the existing PII profiling engine, and on
 * nothing else in Aegivault: no dataset or source persistence, no
 * sanitization, no policy enforcement, no gateway, no audit ledger, no Redis,
 * and no controller. The PII engine knows nothing about PostgreSQL and this
 * milestone does not make it know.
 */
public class PostgresTableProfiler {

    private final PostgresTableRowSource rowSource;

    private final PiiColumnProfiler columnProfiler;

    /**
     * @param rowSource      bounded row streaming over the source boundary, never null
     * @param columnProfiler existing per-column PII profiling engine, never null
     * @throws NullPointerException when either dependency is null
     */
    public PostgresTableProfiler(PostgresTableRowSource rowSource, PiiColumnProfiler columnProfiler) {
        this.rowSource = Objects.requireNonNull(rowSource, "rowSource must not be null");
        this.columnProfiler = Objects.requireNonNull(columnProfiler, "columnProfiler must not be null");
    }

    /**
     * Profiles one discovered table and returns the result in memory.
     *
     * <p>The result is never persisted, logged, or exposed over HTTP by this
     * method: it is returned to the caller and nothing else.
     *
     * @param datasetId caller-supplied identifier recorded on the profile, never
     *                   null; it labels the profile and is used to look nothing up
     * @param source    configured source to read from, never null
     * @param table     the discovered table to profile, never null, with at least
     *                  one discovered column; not a table name and not SQL
     * @return a {@link DatasetProfile} covering exactly the rows streamed, with
     *         one {@link ColumnProfile} per discovered column in ordinal order;
     *         an empty table yields zero counts and no detections rather than a
     *         failure
     * @throws NullPointerException when an argument is null
     * @throws IllegalArgumentException when the source's schema name, the table
     *         name, or any discovered column name is not a plain identifier
     * @throws PostgresSourceConnectionException when the source could not be reached
     * @throws PostgresTableRowReadException when the source was reached but its
     *         rows could not be read
     * @throws PostgresTableProfileException when the rows were read but the PII
     *         analysis over them failed; the message is fixed and carries no SQL,
     *         identifier, credential, driver text, or row value
     */
    public DatasetProfile profile(UUID datasetId, PostgresDataSource source, PostgresTable table) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(table, "table must not be null");
        List<PostgresColumn> columns = table.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a profileable table must have at least one column");
        }

        // One bounded accumulator per column, in ordinal order. The stream's own
        // row ceiling caps how long these can grow, so the memory this costs is
        // governed by the same bound that governs the read.
        List<List<String>> valuesByColumn = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            valuesByColumn.add(new ArrayList<>());
        }
        // Rows arrive one at a time and are dropped as they are routed; no list
        // of rows, no snapshot, and no CSV is built here.
        Consumer<PostgresTableRow> router = row -> route(row, columns.size(), valuesByColumn);

        // A source-layer failure (unreachable, unreadable rows) is not a
        // profiling failure and keeps its own distinct, already-safe exception.
        rowSource.streamRows(source, table, router);

        try {
            return new DatasetProfile(
                    datasetId,
                    profileColumns(columns, valuesByColumn),
                    columns.size(),
                    columnProfiler.maxSampleSize());
        } catch (RuntimeException ex) {
            // A detector that fails while inspecting a value can build a message
            // quoting that value; this is the boundary that stops it escaping.
            throw new PostgresTableProfileException(ex);
        }
    }

    /**
     * Routes one row's values to their columns, converting at this boundary.
     *
     * <p>By position, not by name: the row's values are in the same
     * {@code ORDINAL_POSITION} order as the columns, which is what keeps a
     * value attached to its own column.
     */
    private static void route(
            PostgresTableRow row, int columnCount, List<List<String>> valuesByColumn) {
        for (int index = 0; index < columnCount; index++) {
            valuesByColumn.get(index).add(toProfilingText(row.valueAt(index)));
        }
    }

    private List<ColumnProfile> profileColumns(
            List<PostgresColumn> columns, List<List<String>> valuesByColumn) {
        List<ColumnProfile> profiles = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            profiles.add(columnProfiler.profile(
                    new ColumnInput(columns.get(index).name(), valuesByColumn.get(index))));
        }
        return List.copyOf(profiles);
    }

    /**
     * The one and only value conversion in this milestone.
     *
     * <p><strong>The strategy, in full:</strong>
     *
     * <ul>
     *   <li>SQL {@code NULL} becomes {@code null} — never the string
     *       {@code "null"}, which {@link PiiColumnProfiler} would otherwise
     *       count as an analyzable value and hand to every detector;</li>
     *   <li>a {@link String} is used exactly as the database returned it;</li>
     *   <li>binary ({@code byte[]}, e.g. {@code bytea}) becomes {@code null}:
     *       decoding it would invent text the database never held, so it is
     *       treated as having no analyzable text rather than as a guess;</li>
     *   <li>everything else uses its own {@code toString()} — integers,
     *       decimals, booleans, dates, timestamps, {@link UUID}s, and the
     *       driver's {@code jsonb} object, whose text form is its JSON.</li>
     * </ul>
     *
     * <p>This is conversion only. It does not tokenize, detect language,
     * normalize case, trim, reformat, or otherwise reinterpret a value, so a
     * detector sees the value's own text form and nothing else. It is
     * deterministic, which is what makes repeated profiling of the same rows
     * produce equivalent profiles.
     *
     * @param value JDBC value from a {@link PostgresTableRow}, never a wrapped
     *              result set
     * @return profiling text, or {@code null} for SQL NULL and for binary
     */
    static String toProfilingText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof byte[]) {
            // Binary is not text; decoding it would fabricate content.
            return null;
        }
        return String.valueOf(value);
    }
}
