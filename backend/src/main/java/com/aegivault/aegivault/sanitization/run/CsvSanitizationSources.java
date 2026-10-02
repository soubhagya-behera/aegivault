package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.dataset.DatasetInputSource;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationResult;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationService;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns stored CSV input into a {@link SanitizationContentSource}: the one place
 * that knows how a CSV dataset becomes sanitized bytes and structural counts.
 *
 * <p><strong>This is an adapter, not a lifecycle.</strong> It opens no run, makes
 * no transition, stores no artifact, and appends no audit event.
 * {@link SanitizationRunExecutor} remains the sole lifecycle owner; this class
 * only produces content for it. That separation is what lets the executor run a
 * CSV run and a database run through one identical finish path instead of two
 * near-duplicate ones.
 *
 * <p><strong>Stream ownership is unchanged.</strong>
 * {@link #openStoredCsv(String, UUID)} hands the stream to the caller, and the
 * executor closes it exactly as before. The supplied destination is written and
 * flushed but never closed, so a caller-provided output stream keeps its
 * existing contract.
 *
 * <p>The input is opened <em>before</em> any run row exists, so a missing or
 * foreign dataset fails while nothing has been persisted and sanitization never
 * starts. That ordering is a deliberate part of this adapter's contract and is
 * asserted by the existing executor tests.
 */
@Component
public class CsvSanitizationSources {

    private final CsvSanitizationService csv;

    private final DatasetInputSource inputs;

    public CsvSanitizationSources(CsvSanitizationService csv, DatasetInputSource inputs) {
        this.csv = Objects.requireNonNull(csv, "csv must not be null");
        this.inputs = Objects.requireNonNull(inputs, "inputs must not be null");
    }

    /**
     * Opens the dataset's persisted CSV input for the owner.
     *
     * <p>The returned stream belongs to the caller, which closes it. Failures are
     * propagated unchanged so the executor keeps translating them exactly as it
     * did before this adapter existed.
     *
     * @param ownerSubject owner recorded on the run, never blank
     * @param datasetId dataset whose stored input is read, never null
     * @return the opened input, never null
     */
    public InputStream openStoredCsv(String ownerSubject, UUID datasetId) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        return inputs.openInput(ownerSubject, datasetId);
    }

    /**
     * Builds the content source that sanitizes one CSV input into one
     * destination.
     *
     * @param input already-open CSV input, never null; not closed here
     * @param plan explicit plan applied by the existing engine, never null
     * @param destination sanitized output, written and flushed but never closed
     * @return the content source, never null
     */
    public SanitizationContentSource fromStream(
            InputStream input, TransformationPlan plan, OutputStream destination) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        return output -> toResult(csv.sanitize(input, destination, plan));
    }

    /** Converts the CSV engine's counts into the run record's structural counts. */
    private static RunResult toResult(CsvSanitizationResult result) {
        return new RunResult(
                result.dataRowsRead(),
                result.dataRowsWritten(),
                result.blankRowsSkipped(),
                result.columnCount());
    }
}