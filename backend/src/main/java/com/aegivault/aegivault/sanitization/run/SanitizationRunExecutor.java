package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerException;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.dataset.DatasetInputSource;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.csv.CsvParseException;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationResult;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationService;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.artifact.ArtifactTooLargeException;
import com.aegivault.aegivault.sanitization.artifact.DatabaseArtifactStore;
import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Synchronous orchestration between run persistence and the CSV
 * sanitization engine, covering both terminal outcomes.
 *
 * <p>Responsibilities stay separated: {@link SanitizationRunService} owns
 * run persistence and state, {@link CsvSanitizationService} owns CSV
 * parsing and transformation, and this coordinator only sequences them —
 * create ({@code QUEUED}), start ({@code RUNNING}), sanitize, then complete
 * ({@code COMPLETED}) or fail ({@code FAILED}). No CSV or transformation
 * logic lives here.
 *
 * <p>Each lifecycle step commits in its own transaction (see
 * {@link SanitizationRunService}), so the {@code RUNNING} state is durable
 * while the engine streams; the engine itself runs outside any database
 * transaction. The coordinator holds no transaction on purpose.
 *
 * <p>Caller-supplied streams stay caller-owned: they are never closed here,
 * only passed through to the engine, which flushes output without closing
 * it. The stored-input path is the exception that proves the rule — this
 * coordinator opens that stream itself through {@link DatasetInputSource},
 * so it owns and closes it. That path additionally captures output through
 * a bounded tee into {@link SanitizationArtifactStore} after a successful
 * sanitize but before completion, so a completed stored-input run always
 * has exactly one artifact and a failed run never leaves a partial one;
 * the capture buffer cannot exceed the single artifact bound. Nothing
 * is logged or persisted besides the safe counts in {@link RunResult} or
 * the safe metadata in {@link RunFailure}.
 *
 * <p>Every executed run also appends its lifecycle to the audit ledger —
 * one {@code SANITIZATION_RUN_CREATED} entry after the run is started and
 * one {@code SANITIZATION_RUN_COMPLETED} or {@code SANITIZATION_RUN_FAILED}
 * entry after the terminal transition commits — carrying safe metadata
 * only. Audit appends never change run semantics: documented-safe engine
 * failures still map to {@code FAILED} exactly as below, and an audit
 * infrastructure failure surfaces loudly as {@link AuditLedgerException}
 * instead of being silently skipped or reported as success.
 *
 * <p>Failure mapping is conservative and explicit: only the engine's
 * documented-safe domain exceptions are translated — {@link CsvParseException}
 * (structural facts only) and {@link SanitizationException} (policy metadata
 * only, including {@link MissingTransformationException}) — and their
 * messages are persisted verbatim because both contracts guarantee no raw
 * values, PII, secrets, or stack traces appear in them. {@link RunFailure}
 * length caps bound the stored text as a second line of defense. Anything
 * else (programming errors, infrastructure failures) propagates untouched
 * and the run stays {@code RUNNING}; there are no retries.
 */
@Service
@RequiredArgsConstructor
public class SanitizationRunExecutor {

    private final SanitizationRunService runs;

    private final CsvSanitizationService csv;

    private final DatasetInputSource inputs;

    private final SanitizationArtifactStore artifacts;

    private final AuditLedgerService audit;

    /**
     * Executes one CSV sanitization against an owned dataset.
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId dataset to sanitize, must belong to the owner
     * @param plan explicit plan that is both frozen into the run and used
     *        for execution, never null — the same instance, never
     *        reloaded or reconstructed afterwards
     * @param policyName policy label frozen into the run, never blank
     * @param policyVersion version label frozen into the run, never blank
     * @param input CSV input, caller-owned and never closed here
     * @param output sanitized destination, flushed and never closed here
     * @return the completed run view on success; the failed run view
     *         (status {@code FAILED} with safe failure metadata) when the
     *         engine reports a documented-safe domain failure — no exception
     *         is thrown for those cases
     * @throws ReferencedDatasetNotFoundException when the dataset is missing
     *         or belongs to another owner
     */
    public SanitizationRunView executeCsv(
            String ownerSubject,
            UUID datasetId,
            TransformationPlan plan,
            String policyName,
            String policyVersion,
            InputStream input,
            OutputStream output) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(output, "output must not be null");
        String owner = requireOwner(ownerSubject);
        SanitizationRunView created = runs.createRun(owner, datasetId, plan, policyName, policyVersion);
        SanitizationRunView started = runs.startRun(owner, created.id());
        appendAudit(
                AuditEventData.RUN_CREATED,
                owner,
                created.id(),
                AuditEventData.runCreated(datasetId, policyName, policyVersion));
        return sanitizeCompleteOrFail(owner, started, plan, input, output, null);
    }

    /**
     * Executes one CSV sanitization using the dataset's persisted input
     * instead of a caller-supplied stream.
     *
     * <p>The input is opened through {@link DatasetInputSource} before any
     * run row exists: a missing or foreign dataset/input fails here with
     * {@link ReferencedDatasetNotFoundException} (translated from the
     * input boundary's equally-worded signal), so no run is created and
     * sanitization never starts. Dataset ownership is then enforced again
     * by {@code createRun} — two checks on two resources (stored bytes,
     * dataset row), not one check duplicated. The opened stream belongs to
     * this coordinator and is closed here; the caller-provided output keeps
     * its existing contract (flushed, never closed).
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId dataset to sanitize, must belong to the owner and
     *        have stored input
     * @param plan explicit plan frozen and executed, never null
     * @param policyName policy label frozen into the run, never blank
     * @param policyVersion version label frozen into the run, never blank
     * @param output sanitized destination, flushed and never closed here
     * @return the completed run view on success; the failed run view when
     *         the engine reports a documented-safe domain failure
     * @throws ReferencedDatasetNotFoundException when the dataset or its
     *         stored input is missing or belongs to another owner
     */
    public SanitizationRunView executeStoredCsv(
            String ownerSubject,
            UUID datasetId,
            TransformationPlan plan,
            String policyName,
            String policyVersion,
            OutputStream output) {
        Objects.requireNonNull(output, "output must not be null");
        String owner = requireOwner(ownerSubject);
        try (InputStream input = inputs.openInput(owner, datasetId)) {
            SanitizationRunView created = runs.createRun(owner, datasetId, plan, policyName, policyVersion);
            SanitizationRunView started = runs.startRun(owner, created.id());
            appendAudit(
                    AuditEventData.RUN_CREATED,
                    owner,
                    created.id(),
                    AuditEventData.runCreated(datasetId, policyName, policyVersion));
            BoundedCapture capture = new BoundedCapture(output);
            return sanitizeCompleteOrFail(owner, started, plan, input, capture, capture);
        } catch (DatasetNotFoundException ex) {
            throw new ReferencedDatasetNotFoundException();
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
    }

    /**
     * Executes one <em>already persisted</em> {@code QUEUED} run in the
     * background, using the run's own stored input and frozen policy.
     *
     * <p>This is the entry point an asynchronous job worker uses. It is the
     * same pipeline as {@link #executeStoredCsv} with exactly one difference:
     * the run already exists, so the row is read instead of created. Every
     * concern stays where it was — the run row is the only execution state,
     * the input is opened through {@link DatasetInputSource}, the plan comes
     * from the run's own frozen {@link PolicySnapshot}, the engine is
     * {@link CsvSanitizationService}, the artifact is written by
     * {@link SanitizationArtifactStore} through the same bounded capture, and
     * the lifecycle and its audit events are the ones this class already
     * performs. Nothing is re-implemented here.
     *
     * <p><strong>The persisted state machine is still the only authority.</strong>
     * {@code startRun} performs the {@code QUEUED -> RUNNING} transition and
     * rejects anything else with the same
     * {@link InvalidRunTransitionException} every other path raises, so a
     * worker can never start a run that is already running or terminal. The
     * terminal transition and its audit event come from the same shared core
     * as the synchronous paths.
     *
     * <p><strong>The input is opened before the run is started.</strong> A
     * missing or foreign dataset/input therefore fails while the run is still
     * {@code QUEUED}, exactly as it does before a run row exists on the
     * synchronous path — nothing is marked {@code RUNNING} for work that could
     * not begin. An unexpected failure after the start propagates and leaves
     * the run {@code RUNNING}, which is the existing, documented behaviour.
     *
     * <p>There is no output stream to write to: the artifact store is the
     * destination, so sanitized bytes are captured into the artifact and the
     * caller-facing output is discarded rather than buffered anywhere new.
     *
     * @param runId id of a persisted {@code QUEUED} run, never null
     * @return the completed run view on success; the failed run view when the
     *         engine reports a documented-safe domain failure
     * @throws SanitizationRunNotFoundException when no such run exists
     * @throws InvalidRunTransitionException when the run is not {@code QUEUED}
     * @throws ReferencedDatasetNotFoundException when the run's dataset or its
     *         stored input is missing or belongs to another owner
     */
    public SanitizationRunView executeQueuedRun(UUID runId) {
        SanitizationRunTarget target = runs.loadForExecution(runId);
        String owner = target.ownerSubject();
        try (InputStream input = inputs.openInput(owner, target.datasetId())) {
            SanitizationRunView started = runs.startRun(owner, runId);
            appendAudit(
                    AuditEventData.RUN_CREATED,
                    owner,
                    runId,
                    AuditEventData.runCreated(target.datasetId(), target.policyName(), target.policyVersion()));
            BoundedCapture capture = new BoundedCapture(OutputStream.nullOutputStream());
            return sanitizeCompleteOrFail(
                    owner,
                    started,
                    PolicySnapshot.fromJson(target.policySnapshot()).toPlan(),
                    input,
                    capture,
                    capture);
        } catch (DatasetNotFoundException ex) {
            throw new ReferencedDatasetNotFoundException();
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
    }

    /**
     * Executes one sanitization whose content comes from a source other than
     * stored CSV, through this executor's existing run lifecycle.
     *
     * <p><strong>This is a seam, not a second lifecycle.</strong> The run is
     * created, started, audited, completed, and failed by exactly the same code
     * as the CSV paths, and the artifact is captured and stored by the same
     * bounded capture. The only thing a caller supplies is where sanitized bytes
     * come from — there is still one state machine, one failure mapping, one
     * audit sequence, and one artifact path in the codebase.
     *
     * <p>There is no caller-facing output stream: the
     * {@link SanitizationArtifactStore} is the destination, exactly as for
     * {@link #executeQueuedRun}, so sanitized bytes are captured into the
     * artifact and no second copy is buffered anywhere.
     *
     * <p>A source failure is reported the same way a CSV failure is: the run
     * reaches {@code FAILED} with metadata-only detail and no artifact, rather
     * than an exception escaping with source internals attached.
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId dataset being sanitized, must belong to the owner
     * @param plan explicit plan frozen into the run and applied by the source
     * @param policyName policy label frozen into the run, never blank
     * @param policyVersion version label frozen into the run, never blank
     * @param content supplies the sanitized bytes and counts, never null
     * @return the completed run view on success; the failed run view when the
     *         source reports a documented-safe domain failure
     */
    public SanitizationRunView executeContent(
            String ownerSubject,
            UUID datasetId,
            TransformationPlan plan,
            String policyName,
            String policyVersion,
            SanitizationContentSource content) {
        Objects.requireNonNull(content, "content must not be null");
        String owner = requireOwner(ownerSubject);
        SanitizationRunView created = runs.createRun(owner, datasetId, plan, policyName, policyVersion);
        SanitizationRunView started = runs.startRun(owner, created.id());
        appendAudit(
                AuditEventData.RUN_CREATED,
                owner,
                created.id(),
                AuditEventData.runCreated(datasetId, policyName, policyVersion));
        BoundedCapture capture = new BoundedCapture(OutputStream.nullOutputStream());
        return completeOrFail(owner, started, content, capture);
    }

    /**
     * Shared finish core for a {@link SanitizationContentSource}: runs the
     * source, maps documented-safe domain failures to {@code FAILED}, stores the
     * captured artifact, and completes with the structural counts.
     *
     * <p>The failure mapping is the same one the CSV path uses, extended with
     * {@link com.aegivault.aegivault.sanitization.SanitizationSourceException}
     * so a source that could not be read is distinguishable in the run record
     * from content that could not be transformed — without either carrying a
     * source detail.
     */
    private SanitizationRunView completeOrFail(
            String owner, SanitizationRunView started, SanitizationContentSource content, BoundedCapture capture) {
        RunResult result;
        try {
            result = content.sanitizeTo(capture);
        } catch (com.aegivault.aegivault.sanitization.SanitizationSourceException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("SOURCE_UNAVAILABLE", "SOURCE", ex.getMessage()));
        } catch (CsvParseException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("CSV_PARSE_ERROR", "TOKENIZE", ex.getMessage()));
        } catch (MissingTransformationException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("POLICY_GAP", "TRANSFORM", ex.getMessage()));
        } catch (SanitizationException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("TRANSFORM_ERROR", "TRANSFORM", ex.getMessage()));
        } catch (ArtifactTooLargeException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("OUTPUT_TOO_LARGE", "WRITE", ex.getMessage()));
        }
        // Stored before completion, so a completed run always has exactly one
        // artifact and a failed store never reports completion.
        artifacts.storeArtifact(owner, started.id(), new ByteArrayInputStream(capture.captured()));
        SanitizationRunView completed = runs.completeRun(owner, started.id(), result);
        appendAudit(
                AuditEventData.RUN_COMPLETED,
                owner,
                started.id(),
                AuditEventData.runCompleted(
                        result.inputRows(),
                        result.outputRows(),
                        result.blankRowsSkipped(),
                        result.columnCount()));
        return completed;
    }

    /**
     * Shared sanitize-then-finish core: runs the engine, maps documented
     * domain failures to {@code FAILED}, and completes with the structural
     * counts on success.
     *
     * <p>Each terminal transition is followed by its ledger event, so every
     * finished run contributes exactly two entries — CREATED from the caller
     * above plus COMPLETED or FAILED here — and an unfinished run (an
     * unexpected failure propagating past this method) contributes only
     * CREATED, truthfully. The event is appended after the transition
     * commits, and only for transitions that actually happened.
     *
     * @param capture when non-null, output is captured through it and the
     *        captured bytes are stored as the run's artifact after a
     *        successful sanitize but before completion, so a completed run
     *        never lacks its artifact and a failed store never reports
     *        completion; null when the caller owns output directly and no
     *        artifact applies
     */
    private SanitizationRunView sanitizeCompleteOrFail(
            String owner,
            SanitizationRunView started,
            TransformationPlan plan,
            InputStream input,
            OutputStream csvOutput,
            BoundedCapture capture) {
        CsvSanitizationResult result;
        try {
            result = csv.sanitize(input, csvOutput, plan);
        } catch (CsvParseException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("CSV_PARSE_ERROR", "TOKENIZE", ex.getMessage()));
        } catch (MissingTransformationException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("POLICY_GAP", "TRANSFORM", ex.getMessage()));
        } catch (SanitizationException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("TRANSFORM_ERROR", "TRANSFORM", ex.getMessage()));
        } catch (ArtifactTooLargeException ex) {
            return failAndAudit(
                    owner, started.id(), new RunFailure("OUTPUT_TOO_LARGE", "WRITE", ex.getMessage()));
        }
        if (capture != null) {
            artifacts.storeArtifact(owner, started.id(), new ByteArrayInputStream(capture.captured()));
        }
        SanitizationRunView completed = runs.completeRun(
                owner,
                started.id(),
                new RunResult(
                        result.dataRowsRead(),
                        result.dataRowsWritten(),
                        result.blankRowsSkipped(),
                        result.columnCount()));
        appendAudit(
                AuditEventData.RUN_COMPLETED,
                owner,
                started.id(),
                AuditEventData.runCompleted(
                        result.dataRowsRead(),
                        result.dataRowsWritten(),
                        result.blankRowsSkipped(),
                        result.columnCount()));
        return completed;
    }

    /**
     * Persists one {@code FAILED} transition and then its ledger event.
     * Engine failure semantics are unchanged: the same view is returned
     * that {@code failRun} alone would have produced.
     */
    private SanitizationRunView failAndAudit(String owner, UUID runId, RunFailure failure) {
        SanitizationRunView failed = runs.failRun(owner, runId, failure);
        appendAudit(
                AuditEventData.RUN_FAILED,
                owner,
                runId,
                AuditEventData.runFailed(failure.errorCode(), failure.errorStage()));
        return failed;
    }

    /**
     * Appends one run lifecycle event. An audit infrastructure failure is
     * never swallowed and never reported as success: it surfaces as a
     * generic {@link AuditLedgerException} (cause retained for server logs,
     * no storage details in the message) and the caller propagates it.
     */
    private void appendAudit(String eventType, String owner, UUID runId, String eventData) {
        try {
            audit.append(eventType, owner, AuditEventData.SANITIZATION_RUN_RESOURCE, runId, eventData);
        } catch (RuntimeException ex) {
            throw new AuditLedgerException("Unable to record audit event.", ex);
        }
    }

    /**
     * Forwards every byte to the caller's stream while retaining a bounded
     * copy for artifact storage. The buffer never exceeds
     * {@link DatabaseArtifactStore#MAX_ARTIFACT_BYTES} (the single artifact
     * bound, reused here rather than redefined): beyond it sanitization
     * aborts with {@link ArtifactTooLargeException}, so no truncation and
     * no partial artifact are possible. Never closes the caller's stream.
     */
    private static final class BoundedCapture extends OutputStream {

        private final OutputStream downstream;

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private long total;

        private BoundedCapture(OutputStream downstream) {
            this.downstream = Objects.requireNonNull(downstream, "downstream must not be null");
        }

        @Override
        public void write(int singleByte) throws IOException {
            write(new byte[] {(byte) singleByte}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            total += length;
            if (total > DatabaseArtifactStore.MAX_ARTIFACT_BYTES) {
                throw new ArtifactTooLargeException(DatabaseArtifactStore.MAX_ARTIFACT_BYTES);
            }
            downstream.write(bytes, offset, length);
            captured.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            downstream.flush();
        }

        private byte[] captured() {
            return captured.toByteArray();
        }
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
