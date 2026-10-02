package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerException;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.csv.CsvParseException;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationService;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.SanitizationSourceException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.artifact.ArtifactTooLargeException;
import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import java.io.ByteArrayInputStream;
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
 * <p>Responsibilities stay separated: {@link SanitizationRunService} owns run
 * persistence and state, {@link CsvSanitizationSources} turns stored CSV into
 * sanitized content, {@link SanitizationRunSourceDispatcher} selects a
 * non-CSV content source, and this coordinator only sequences them — create
 * ({@code QUEUED}), start ({@code RUNNING}), sanitize, then complete
 * ({@code COMPLETED}) or fail ({@code FAILED}). It is the single place that
 * transitions a run, captures its artifact, audits it, and maps a failure, and
 * no source-specific class holds any of that authority. No CSV or transformation
 * logic lives here.
 *
 * <p>Each lifecycle step commits in its own transaction (see
 * {@link SanitizationRunService}), so the {@code RUNNING} state is durable
 * while the engine streams; the engine itself runs outside any database
 * transaction. The coordinator holds no transaction on purpose.
 *
 * <p>Caller-supplied streams stay caller-owned: they are never closed here, only
 * passed through to the engine, which flushes output without closing it. The
 * stored-input path is the exception that proves the rule — it opens that stream
 * itself, so it owns and closes it. Stream and capture ownership is documented on
 * {@link CsvSanitizationSources} and {@link BoundedCapture}; that path captures
 * into {@link SanitizationArtifactStore} after a successful sanitize but before
 * completion, so a completed run always has exactly one artifact and a failed run
 * never leaves a partial one. Nothing is logged or persisted besides the safe
     * counts in {@link RunResult}/{@link RunFailure}.
 *
 * <p>Every executed run also appends its lifecycle to the audit ledger: one
 * {@code SANITIZATION_RUN_CREATED} entry once the run is started, and one
 * {@code SANITIZATION_RUN_COMPLETED} or {@code SANITIZATION_RUN_FAILED} entry
 * after the terminal transition commits — safe metadata only. Audit appends never
 * change run semantics, and an audit infrastructure failure surfaces loudly as
 * {@link AuditLedgerException} rather than being silently skipped.
 *
 * <p>Failure mapping is conservative and explicit: only the engine's
 * documented-safe domain exceptions are translated — {@link CsvParseException}
 * (structural facts only) and {@link SanitizationException} (policy metadata
 * only, including {@link MissingTransformationException}) — and their messages
 * are persisted verbatim because both contracts guarantee no raw values, PII,
 * secrets, or stack traces appear in them. {@link RunFailure} length caps bound
 * the stored text as a second line of defense. Anything else (programming
 * errors, infrastructure failures) propagates untouched and the run stays
 * {@code RUNNING}; there are no retries.
 */
@Service
@RequiredArgsConstructor
public class SanitizationRunExecutor {

    private final SanitizationRunService runs;

    private final SanitizationRunSourceDispatcher dispatcher;

    private final CsvSanitizationSources csvSources;

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
        SanitizationRunView started = createAndStart(owner, datasetId, plan, policyName, policyVersion);
        return completeOrFail(owner, started, csvSources.fromStream(input, plan, output), null);
    }

    /**
     * Executes one CSV sanitization using the dataset's persisted input
     * instead of a caller-supplied stream.
     *
     * <p>The input is opened before any run row exists: a missing or foreign
     * dataset/input fails here with {@link ReferencedDatasetNotFoundException}
     * (translated from the input boundary's equally-worded signal), so no run is
     * created and sanitization never starts. Dataset ownership is then enforced
     * again by {@code createRun} — two checks on two resources (stored bytes,
     * dataset row), not one check duplicated. The opened stream belongs to this
     * coordinator and is closed here; the caller's output keeps its existing
     * contract (flushed, never closed).
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
        try (InputStream input = csvSources.openStoredCsv(owner, datasetId)) {
            SanitizationRunView started = createAndStart(owner, datasetId, plan, policyName, policyVersion);
            BoundedCapture capture = new BoundedCapture(output);
            return completeOrFail(owner, started, csvSources.fromStream(input, plan, capture), capture);
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
     * <p>This is the entry point an asynchronous job worker uses. It is the same
     * pipeline as {@link #executeStoredCsv} with one difference: the run already
     * exists, so the row is read instead of created, and the recorded
     * {@link SanitizationSourceType} decides which content source runs.
     *
     * <p><strong>The persisted state machine is still the only authority.</strong>
     * {@code startRun} performs the {@code QUEUED -> RUNNING} transition and rejects
     * anything else with the same {@link InvalidRunTransitionException} every other
     * path raises, and the terminal transition and audit come from the same shared
     * core as the synchronous paths.
     *
     * <p><strong>The input is opened before the run is started</strong>, so a
     * missing or foreign dataset/input fails while the run is still {@code QUEUED}.
     * An unexpected failure after the start propagates and leaves the run
     * {@code RUNNING}, which is the existing, documented behaviour. There is no
     * output stream to write to: the artifact store is the destination.
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
        TransformationPlan plan = PolicySnapshot.fromJson(target.policySnapshot()).toPlan();

        if (target.sourceType() != SanitizationSourceType.CSV) {
            return executeQueuedForeignSource(owner, target, plan);
        }
        try (InputStream input = csvSources.openStoredCsv(owner, target.datasetId())) {
            SanitizationRunView started = startAndAudit(owner, target.id(), target.datasetId(), target.policyName(), target.policyVersion());
            BoundedCapture capture = new BoundedCapture(OutputStream.nullOutputStream());
            return completeOrFail(owner, started, csvSources.fromStream(input, plan, capture), capture);
        } catch (DatasetNotFoundException ex) {
            throw new ReferencedDatasetNotFoundException();
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
    }

    /**
     * Executes a queued run whose source is not the stored CSV input.
     *
     * <p><strong>The same lifecycle, through a different content source.</strong>
     * Started, audited, completed or failed, and artifact-captured by exactly the
     * same core the CSV path uses — this adds dispatch, not a second lifecycle.
     * Selection semantics live in {@link SanitizationRunSourceDispatcher}; the
     * plan comes from the run's own frozen snapshot, so a policy edited or
     * deleted after queueing cannot change what this run does.
     *
     * <p><strong>Resolution happens after the start transition.</strong> A
     * PostgreSQL run is {@code RUNNING} before its binding is resolved, so a
     * table that has since disappeared fails through the normal {@code FAILED}
     * path — safe metadata, no artifact — instead of leaving a queued run that
     * can never start.
     */
    private SanitizationRunView executeQueuedForeignSource(
            String owner, SanitizationRunTarget target, TransformationPlan plan) {
        SanitizationRunView started = startAndAudit(owner, target.id(), target.datasetId(), target.policyName(), target.policyVersion());
        SanitizationContentSource content;
        try {
            // Resolved after the start transition, so that a source which cannot
            // be resolved at all — no registered provider, no configured source,
            // a binding whose table has since disappeared — fails this run
            // through the normal FAILED mapping rather than leaving a run that
            // is RUNNING forever or QUEUED with no way forward.
            content = dispatcher.contentFor(target.sourceType(), owner, target.datasetId(), plan);
        } catch (SanitizationSourceException ex) {
            return failAndAudit(
                    owner,
                    target.id(),
                    new RunFailure("SOURCE_UNAVAILABLE", "SOURCE", ex.getMessage()));
        }
        BoundedCapture capture = new BoundedCapture(OutputStream.nullOutputStream());
        return completeOrFail(owner, started, content, capture);
    }

    /**
     * Moves a freshly created run to {@code RUNNING} and records the creation
     * event.
     *
     * <p>Shared by every create-then-execute path so the two cannot drift apart.
     */
    private SanitizationRunView createAndStart(
            String owner, UUID datasetId, TransformationPlan plan, String policyName, String policyVersion) {
        SanitizationRunView created = runs.createRun(owner, datasetId, plan, policyName, policyVersion);
        return startAndAudit(owner, created.id(), datasetId, policyName, policyVersion);
    }

    /** Moves a run to {@code RUNNING} and records its creation event. */
    private SanitizationRunView startAndAudit(
            String owner, UUID runId, UUID datasetId, String policyName, String policyVersion) {
        SanitizationRunView started = runs.startRun(owner, runId);
        appendAudit(
                AuditEventData.RUN_CREATED,
                owner,
                runId,
                AuditEventData.runCreated(datasetId, policyName, policyVersion));
        return started;
    }

    /**
     * Executes one sanitization whose content comes from a source other than
     * stored CSV, through this executor's existing run lifecycle.
     *
     * <p><strong>This is a seam, not a second lifecycle.</strong> The run is created,
     * started, audited, completed, and failed by exactly the same code as the CSV
     * paths, and the artifact is captured and stored by the same bounded capture.
     * The only thing a caller supplies is where sanitized bytes come from — there
     * is still one state machine, one failure mapping, one audit sequence, and
     * one artifact path in the codebase. There is no caller-facing output stream:
     * the {@link SanitizationArtifactStore} is the destination, so no second copy
     * is buffered anywhere, and a source failure is reported exactly as a CSV
     * failure is — {@code FAILED} with metadata-only detail and no artifact.
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
        SanitizationRunView started = createAndStart(owner, datasetId, plan, policyName, policyVersion);
        BoundedCapture capture = new BoundedCapture(OutputStream.nullOutputStream());
        return completeOrFail(owner, started, content, capture);
    }

    /**
     * Shared finish core for a {@link SanitizationContentSource}: runs the
     * source, maps documented-safe domain failures to {@code FAILED}, stores the
     * captured artifact, and completes with the structural counts.
     *
     * <p>The failure mapping is the same one the CSV path always used, extended
     * with {@link SanitizationSourceException} so a source that could not be read
     * is distinguishable in the run record from content that could not be
     * transformed — without either carrying a source detail.
     */
    private SanitizationRunView completeOrFail(
            String owner, SanitizationRunView started, SanitizationContentSource content, BoundedCapture capture) {
        RunResult result;
        try {
            // The adapter decides where the bytes go: a stored-input or
            // asynchronous run writes into the capture, while a caller-owned
            // output stream is written directly and has no capture at all.
            result = content.sanitizeTo(capture);
        } catch (SanitizationSourceException ex) {
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
        // artifact and a failed store never reports completion. A caller-owned
        // output stream has no capture and therefore no artifact, exactly as
        // before this core was shared with that path.
        if (capture != null) {
            artifacts.storeArtifact(owner, started.id(), new ByteArrayInputStream(capture.captured()));
        }
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

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
