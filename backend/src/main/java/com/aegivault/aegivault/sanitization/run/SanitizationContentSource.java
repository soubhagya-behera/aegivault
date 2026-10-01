package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.sanitization.SanitizationSourceException;
import java.io.OutputStream;

/**
 * Supplies one run's sanitized content to the existing
 * {@link SanitizationRunExecutor} lifecycle, whatever the underlying source is.
 *
 * <p><strong>Why this seam exists.</strong> The executor already owns the whole
 * run contract: {@code QUEUED -> RUNNING}, the terminal transition, the audit
 * events, the bounded artifact capture, and the failure mapping. Only the
 * "produce sanitized bytes and counts" step was hard-wired to a CSV
 * {@code InputStream}. This interface is that one step, so a new source can reuse
 * the identical lifecycle instead of duplicating it — there is still exactly one
 * run state machine, one failure mapping, and one artifact path.
 *
 * <p><strong>The contract is narrow on purpose.</strong> An implementation
 * writes sanitized output to the stream it is given and reports structural
 * counts. It never creates or transitions a run, never writes an artifact, never
 * appends an audit event, and never decides that a run has succeeded — the
 * executor does all of that after this call returns. Implementations therefore
 * cannot invent a second lifecycle or skip the audit trail.
 *
 * <p><strong>Stream ownership is explicit.</strong> The supplied stream is
 * caller-owned: an implementation writes and flushes it but must never close it.
 * Nothing here reads input, so there is no input stream to close either; each
 * implementation owns whatever source resources it opens and must release them
 * itself, including on the failure path.
 *
 * <p><strong>Failures are reported, not thrown as control flow.</strong> A
 * documented-safe failure — an unreachable source, a table that no longer
 * exists, a missing transformation — is raised as this package's own safe
 * exception and translated by the executor into a {@code FAILED} run with
 * metadata-only detail, exactly as a CSV failure is today.
 */
@FunctionalInterface
public interface SanitizationContentSource {

    /**
     * Writes this run's sanitized content and reports structural counts.
     *
     * <p>Implementations stream: they must not buffer the whole result in memory.
     *
     * @param output destination for sanitized bytes, never null; written and
     *        flushed but never closed by the implementation
     * @return the structural counts recorded on the completed run; carries no
     *         cell value, column name, or raw data
     * @throws SanitizationSourceException when the source could not supply
     *         content at all
     * @throws com.aegivault.aegivault.sanitization.MissingTransformationException
     *         when the plan does not cover a detected type
     * @throws com.aegivault.aegivault.sanitization.SanitizationException when no
     *         implementation is registered for a resolved strategy
     * @throws com.aegivault.aegivault.sanitization.artifact.ArtifactTooLargeException
     *         when the sanitized output exceeds the artifact bound
     */
    RunResult sanitizeTo(OutputStream output);
}