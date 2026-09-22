package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.dataset.csv.CsvParseException;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationResult;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationService;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
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
 * <p>Streams stay caller-owned: they are never closed here, only passed
 * through to the engine, which flushes output without closing it. Nothing
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
        CsvSanitizationResult result;
        try {
            result = csv.sanitize(input, output, plan);
        } catch (CsvParseException ex) {
            return runs.failRun(
                    owner, started.id(), new RunFailure("CSV_PARSE_ERROR", "TOKENIZE", ex.getMessage()));
        } catch (MissingTransformationException ex) {
            return runs.failRun(
                    owner, started.id(), new RunFailure("POLICY_GAP", "TRANSFORM", ex.getMessage()));
        } catch (SanitizationException ex) {
            return runs.failRun(
                    owner, started.id(), new RunFailure("TRANSFORM_ERROR", "TRANSFORM", ex.getMessage()));
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

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
