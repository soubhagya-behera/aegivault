package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.dataset.csv.CsvSanitizationWriter;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresRowStreamResult;
import com.aegivault.aegivault.dataset.postgres.PostgresSchema;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryException;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRow;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRowReadException;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRowSource;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.pii.PiiDetection;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.sanitization.DataSanitizationService;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.run.RunResult;
import com.aegivault.aegivault.sanitization.run.SanitizationContentSource;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Sanitizes one bound PostgreSQL dataset into a sanitized CSV artifact, through
 * the existing transformation engine and the existing run lifecycle.
 *
 * <p><strong>The bridge, and nothing else.</strong>
 *
 * <pre>
 *   ownerSubject + datasetId + caller's TransformationPlan
 *       -&gt; PostgresDatasetBindingService.get   (owner-scoped: schema + table)
 *       -&gt; PostgresSchemaDiscoveryService     (re-confirmed base table)
 *       -&gt; PostgresTableRowSource              (bounded, read-only, one row at a time)
 *       -&gt; DataSanitizationService             (existing engine, the caller's plan)
 *       -&gt; CsvSanitizationWriter               (existing CSV escaping, UTF-8, LF)
 *       -&gt; SanitizationRunExecutor.executeContent
 *            -&gt; QUEUED -&gt; RUNNING -&gt; COMPLETED | FAILED, audit, artifact
 * </pre>
 *
 * <p><strong>No transformation logic is re-implemented.</strong> Detection stays
 * in {@link PiiDetectorRegistry} and masking, hashing, redaction, and synthesis
 * stay in {@link DataSanitizationService} and its registered strategies. The
 * only PostgreSQL-specific code here decides <em>which</em> table to read and how
 * to render one row as one CSV record.
 *
 * <p><strong>The binding is the sole source of the table.</strong> Schema and
 * table come from the owner-scoped binding lookup and from nowhere else — there
 * is no table parameter, no SQL parameter, and no credential on this API. The
 * bound table is then re-confirmed through the same metadata discovery boundary
 * used when it was bound, so a binding whose table has been dropped, renamed, or
 * replaced by a view fails safely before any row is read: no artifact, no
 * partial artifact, and the binding itself is left untouched rather than
 * deleted or silently repointed.
 *
 * <p><strong>The source is never written.</strong> Rows are read through
 * {@link PostgresTableRowSource} over the existing read-only
 * {@link PostgresDataSource}, which issues a single {@code SELECT} and nothing
 * else. There is no UPDATE, DELETE, DDL, or any other statement anywhere in this
 * package, and the caller cannot reach a connection or a statement to issue one
 * with.
 *
 * <p><strong>Streaming, not loading.</strong> Rows arrive one at a time and each
 * is written and released before the next is read. Nothing accumulates: no list
 * of rows, no full-table CSV string, no row cache. Peak memory is a function of
 * the fetch size and the widest single row, not of the table's size. The only
 * buffer is the artifact store's own existing bound.
 *
 * <p><strong>Bounded, and honest about it.</strong> Only the rows the existing
 * row stream actually delivers are sanitized, up to its configured
 * {@code max-rows}. This milestone does not claim a full-table sanitization:
 * the run entity has no field for "the source stream was truncated", so
 * rather than inventing a run field or overloading an existing one with a
 * meaning it was not designed for, the truncation signal is returned to the
 * caller in {@link PostgresSanitizationResult} and nothing is persisted that
 * would misrepresent the run as having covered the whole table. Recording it on
 * the run is a deliberate future decision for the source-management API.
 *
 * <p><strong>Nulls are explicit.</strong> A SQL NULL becomes an empty CSV field —
 * stated here because CSV has no null of its own, and because it is a decision
 * rather than an accident. The transformation engine is never handed a null.
 *
 * <p><strong>PII safety.</strong> Source values exist in memory only for the
 * duration of one row's processing. Nothing here logs a value, puts one in an
 * exception or its message, persists one, or sends one to an audit event; the
 * audit events are the existing run-lifecycle ones, carrying counts only. Only
 * sanitized text reaches the writer.
 *
 * <p><strong>Ownership is threaded, never widened.</strong> The owner is trimmed
 * once, rejected when blank, and passed to both the binding lookup and the run
 * executor. There is no ADMIN bypass: a foreign dataset or binding is
 * indistinguishable from a missing one.
 *
 * <p><strong>Synchronous, for now.</strong> This service runs inline.
 * {@code SanitizationRunJobLauncher} is untouched; connecting this work to the
 * queued-job path is a separate milestone.
 *
 * <p><strong>Dependency direction is one-way and narrow:</strong> the binding
 * service, the discovery service, the row source, the existing PII and
 * sanitization engines, the existing CSV writer, and the existing run executor.
 */
@Service
public class PostgresDatasetSanitizationService {
    private final PostgresDatasetBindingService bindings;

    private final PostgresSchemaDiscoveryService discovery;

    private final SanitizationRunExecutor runs;

    private final PiiDetectorRegistry detectors;

    private final DataSanitizationService sanitization;

    /** Optional: absent when no PostgreSQL source is configured. */
    private final ObjectProvider<PostgresDataSource> source;

    /** Optional: absent when no PostgreSQL source is configured. */
    private final ObjectProvider<PostgresTableRowSource> rowSources;

    public PostgresDatasetSanitizationService(
            PostgresDatasetBindingService bindings,
            PostgresSchemaDiscoveryService discovery,
            SanitizationRunExecutor runs,
            PiiDetectorRegistry detectors,
            DataSanitizationService sanitization,
            ObjectProvider<PostgresDataSource> source,
            ObjectProvider<PostgresTableRowSource> rowSources) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.detectors = Objects.requireNonNull(detectors, "detectors must not be null");
        this.sanitization = Objects.requireNonNull(sanitization, "sanitization must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.rowSources = Objects.requireNonNull(rowSources, "rowSources must not be null");
    }

    /**
     * Sanitizes the PostgreSQL table bound to an owned dataset into a sanitized
     * CSV artifact on the dataset's next sanitization run.
     *
     * @param ownerSubject   calling owner, never blank (the JWT subject only);
     *                      must own the dataset and its binding
     * @param datasetId      bound dataset to sanitize, never null
     * @param plan           explicit transformation plan supplied by the caller,
     *                      never null; no policy is inferred here
     * @param policyName     policy label frozen into the run, never blank
     * @param policyVersion  version label frozen into the run, never blank
     * @return the run outcome plus the bounded-stream summary, including whether
     *         the source row limit truncated the read
     * @throws IllegalArgumentException when the owner is blank
     * @throws com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException
     *         when the dataset is missing, belongs to another owner, or has no
     *         PostgreSQL binding (identical either way, and silent about the
     *         source)
     * @throws PostgresDatasetSanitizationException when no source is configured,
     *         or the bound table is no longer a discovered base table
     */
    public PostgresSanitizationResult sanitize(
            String ownerSubject,
            UUID datasetId,
            TransformationPlan plan,
            String policyName,
            String policyVersion) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(plan, "plan must not be null");

        // The owner-scoped binding is the only place schema and table come from.
        PostgresDatasetBinding binding = bindings.get(owner, datasetId);

        PostgresDataSource configured = source.getIfAvailable();
        PostgresTableRowSource rows = rowSources.getIfAvailable();
        if (configured == null || rows == null) {
            throw new PostgresDatasetSanitizationException(
                    new IllegalStateException("no PostgreSQL source is configured"));
        }
        // Re-confirmed before a run is created and before any row is read, so a
        // stale binding never produces a run, an artifact, or a partial one.
        PostgresTable table = rediscoverBoundTable(configured, binding);

        // The executor owns the whole lifecycle from here: the run row, the
        // transitions, the audit events, the bounded artifact capture, and the
        // failure mapping. This service only supplies the bytes.
        //
        // The truncation flag is captured per call rather than held on the bean:
        // a shared field would be a cross-request data race, and two concurrent
        // sanitizations must never read each other's stream result.
        boolean[] truncated = {false};
        SanitizationRunView run = runs.executeContent(
                owner, datasetId, plan, policyName, policyVersion,
                output -> streamSanitizedCsv(configured, rows, table, plan, output, truncated));
        return PostgresSanitizationResult.of(run, truncated[0]);
    }
    /** Re-confirms the bound table through metadata discovery, or fails safely. */
    private PostgresTable rediscoverBoundTable(
            PostgresDataSource configured, PostgresDatasetBinding binding) {
        // One configured schema only: honouring a binding recorded for a
        // different schema would mean either browsing across schemas or silently
        // redirecting, so it fails instead.
        if (!configured.schemaName().equals(binding.getSchemaName())) {
            throw new PostgresDatasetSanitizationException(
                    new IllegalStateException("bound schema is not the configured source schema"));
        }
        try {
            return discovery.discover(configured)
                    .table(binding.getTableName())
                    // Empty means the bound table is gone or is no longer a base
                    // table. The binding is left exactly as it is.
                    .orElseThrow(() -> new PostgresDatasetSanitizationException(
                            new IllegalStateException("bound table is no longer a discovered base table")));
        } catch (PostgresSourceConnectionException | PostgresSchemaDiscoveryException ex) {
            throw new PostgresDatasetSanitizationException(ex);
        }
    }

    /**
     * Streams the table's rows through the existing engine into sanitized CSV,
     * one row at a time, and reports the structural counts.
     *
     * <p>The header is the discovered column names in {@code ORDINAL_POSITION}
     * order, so artifact columns line up with the source's own order rather than
     * an alphabetical or caller-supplied one. Nothing is retained between rows:
     * the per-row list is a fresh short-lived field list handed straight to the
     * writer, and the writer buffers at most one record.
     */
    private RunResult streamSanitizedCsv(
            PostgresDataSource configured,
            PostgresTableRowSource rows,
            PostgresTable table,
            TransformationPlan plan,
            OutputStream output,
            boolean[] truncated) {
        CsvSanitizationWriter writer = new CsvSanitizationWriter(output);
        writer.writeRecord(columnNames(table));

        PostgresRowStreamResult[] stream = new PostgresRowStreamResult[1];
        try {
            stream[0] = rows.streamRows(configured, table, row -> writer.writeRecord(sanitizeRow(row, plan)));
        } catch (PostgresSourceConnectionException | PostgresTableRowReadException ex) {
            // The row source already closed its result set, statement, and
            // connection before propagating, so nothing is left open here.
            throw new PostgresDatasetSanitizationException(ex);
        } finally {
            writer.flush();
        }
        truncated[0] = stream[0].rowLimitReached();
        long rowsWritten = stream[0].rowsRead();
        return new RunResult(rowsWritten, rowsWritten, 0, table.columns().size());
    }

    /** The discovered column names, in ordinal order, as the CSV header. */
    private static List<String> columnNames(PostgresTable table) {
        List<String> names = new ArrayList<>(table.columns().size());
        for (var column : table.columns()) {
            names.add(column.name());
        }
        return names;
    }

    /**
     * Transforms one row into one CSV record.
     *
     * <p>Detection is the existing registry's and transformation is the caller's
     * plan applied by the existing engine; this loop only pairs a value with its
     * column position. A value with no detection is passed through unchanged,
     * which is what keeps clean data intact.
     */
    private List<String> sanitizeRow(PostgresTableRow row, TransformationPlan plan) {
        List<String> record = new ArrayList<>(row.columnCount());
        for (int index = 0; index < row.columnCount(); index++) {
            record.add(sanitizeValue(row.valueAt(index), plan));
        }
        return record;
    }

    private String sanitizeValue(Object value, TransformationPlan plan) {
        String text = PostgresRowSanitizer.toCsvField(value);
        if (text.isEmpty()) {
            // SQL NULL and binary are already rendered as an empty field; a blank
            // value carries no PII and rewriting it would fabricate content.
            return text;
        }
        List<PiiDetection> detections = detectors.detect(text);
        if (detections.isEmpty()) {
            return text;
        }
        // The same first-detection-wins rule the CSV path applies, so both
        // sources choose identically when a value matches several detectors.
        return sanitization.sanitize(text, detections.get(0).type(), plan);
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}