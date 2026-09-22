package com.aegivault.aegivault.sanitization.run;

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
            BoundedCapture capture = new BoundedCapture(output);
            return sanitizeCompleteOrFail(owner, started, plan, input, capture, capture);
        } catch (DatasetNotFoundException ex) {
            throw new ReferencedDatasetNotFoundException();
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
    }

    /**
     * Shared sanitize-then-finish core: runs the engine, maps documented
     * domain failures to {@code FAILED}, and completes with the structural
     * counts on success.
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
            return runs.failRun(
                    owner, started.id(), new RunFailure("CSV_PARSE_ERROR", "TOKENIZE", ex.getMessage()));
        } catch (MissingTransformationException ex) {
            return runs.failRun(
                    owner, started.id(), new RunFailure("POLICY_GAP", "TRANSFORM", ex.getMessage()));
        } catch (SanitizationException ex) {
            return runs.failRun(
                    owner, started.id(), new RunFailure("TRANSFORM_ERROR", "TRANSFORM", ex.getMessage()));
        } catch (ArtifactTooLargeException ex) {
            return runs.failRun(
                    owner, started.id(), new RunFailure("OUTPUT_TOO_LARGE", "WRITE", ex.getMessage()));
        }
        if (capture != null) {
            artifacts.storeArtifact(owner, started.id(), new ByteArrayInputStream(capture.captured()));
        }
        return runs.completeRun(
                owner,
                started.id(),
                new RunResult(
                        result.dataRowsRead(),
                        result.dataRowsWritten(),
                        result.blankRowsSkipped(),
                        result.columnCount()));
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
