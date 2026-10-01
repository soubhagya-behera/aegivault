package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import java.util.UUID;

/**
 * What one PostgreSQL sanitization produced: the run's own outcome, plus the
 * bounded-stream facts the run model does not record.
 *
 * <p><strong>Why the truncation flag lives here and not on the run.</strong>
 * {@link SanitizationRunView} and the run entity have no field for "the source
 * stream stopped at its row ceiling". Overloading an existing count to mean
 * "this was the whole table" would be wrong, and adding a run field in this
 * milestone would invent persistence semantics the source-management API should
 * decide. So the existing run keeps recording exactly the structural counts it
 * always has, and the one fact it cannot express is returned to the caller here
 * instead. Nothing persisted claims full-table coverage.
 *
 * <p><strong>Carries no data.</strong> A run id, a status, two counts, and a
 * boolean. No cell value, no column name, no table name, no raw data — so this
 * record is safe to log, aggregate, or return to a caller.
 *
 * <p>Only sanitized rows are counted. {@code rowsSanitized} is the number of rows
 * actually written to the artifact, which equals the number the bounded stream
 * delivered; when {@link #rowLimitReached()} is true, more rows may exist in the
 * source.
 *
 * @param run the run created for this sanitization, never null
 * @param rowsSanitized rows written to the sanitized artifact, never negative
 * @param rowLimitReached whether the source row ceiling stopped the stream,
 *        meaning the source may hold rows that were not sanitized
 */
public record PostgresSanitizationResult(
        SanitizationRunView run, long rowsSanitized, boolean rowLimitReached) {

    public PostgresSanitizationResult {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        if (rowsSanitized < 0) {
            throw new IllegalArgumentException("rowsSanitized must not be negative");
        }
    }

    /**
     * Wraps a run outcome with the stream's truncation signal.
     *
     * @param run the completed or failed run, never null
     * @param rowLimitReached whether the source row ceiling stopped the stream
     * @return the combined result, never null
     */
    static PostgresSanitizationResult of(SanitizationRunView run, boolean rowLimitReached) {
        // A failed run never records an output count, so it sanitizes zero rows.
        // The run's own counts stay authoritative and are not adjusted here.
        Long outputRows = run.outputRowCount();
        return new PostgresSanitizationResult(
                run, outputRows == null ? 0L : outputRows, rowLimitReached);
    }

    /**
     * The id of the run this result came from.
     *
     * @return the run id, never null
     */
    public UUID runId() {
        return run.id();
    }
}