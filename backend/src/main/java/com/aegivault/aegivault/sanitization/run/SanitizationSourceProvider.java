package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.sanitization.TransformationPlan;
import java.util.UUID;

/**
 * Supplies the sanitized content for one already-persisted {@code QUEUED} run of
 * a particular {@link SanitizationSourceType}.
 *
 * <p><strong>Why this interface exists.</strong> The run package must be able to
 * dispatch a PostgreSQL run to the PostgreSQL path without depending on the
 * PostgreSQL package. This interface is the seam that keeps the dependency
 * pointing one way: the run package declares what it needs, the PostgreSQL package
 * implements it, and the executor selects by
 * {@link #sourceType()}. Nothing in {@code sanitization.run} imports a
 * {@code dataset.postgres} class, so a checkout with no PostgreSQL source is not
 * a special case anywhere in the run machinery.
 *
 * <p><strong>An implementation supplies bytes; it owns no lifecycle.</strong>
 * Given the run's own recorded owner and dataset, it returns a
 * {@link SanitizationContentSource} that writes sanitized output and reports
 * structural counts. It does not create or transition a run, store an artifact,
 * or append an audit event — the executor does all of that, so there is still
 * exactly one lifecycle and one audit sequence.
 *
 * <p><strong>Everything mutable is re-read at execution time.</strong> The only
 * inputs are the owner recorded on the run row, the run's dataset, and the plan
 * rebuilt from the run's own frozen {@code PolicySnapshot}. No request body, no
 * current policy contents, and no caller-supplied schema, table, or credential is
 * accepted, so a queued run cannot be steered at execution time.
 */
public interface SanitizationSourceProvider {

    /**
     * The one source kind this provider handles.
     *
     * @return the handled source type, never null
     */
    SanitizationSourceType sourceType();

    /**
     * Builds the content source for one queued run of this kind.
     *
     * <p>Implementations must resolve everything they need from the run's own
     * owner and dataset — for a database source, the dataset's persisted binding
     * and the application's configured datasource — and must do so lazily inside
     * the returned source, so that a source which has become unusable fails the
     * run through the normal failure mapping rather than escaping this call.
     *
     * @param ownerSubject owner recorded on the run row, never blank; the only
     *        owner used, so no caller can redirect the run
     * @param datasetId dataset recorded on the run row, never null
     * @param plan plan rebuilt from the run's frozen policy snapshot, never null
     * @return the content source, never null; it performs no database work yet
     */
    SanitizationContentSource contentFor(String ownerSubject, UUID datasetId, TransformationPlan plan);
}