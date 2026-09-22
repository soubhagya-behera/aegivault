package com.aegivault.aegivault.sanitization.artifact;

import com.aegivault.aegivault.sanitization.run.SanitizationRun;
import java.io.InputStream;
import java.util.UUID;

/**
 * Owner-scoped store for sanitized CSV output artifacts, one per
 * {@link SanitizationRun}.
 *
 * <p>Contract for implementations:
 *
 * <ul>
 *   <li>Verify {@code ownerSubject} owns the run through the existing
 *       owner-scoped run lookup. A missing run, another owner's run, and a
 *       missing artifact must behave identically.</li>
 *   <li>Return a fresh {@link InputStream} on every read. The caller owns
 *       the stream: it must be closed by the caller, never by the
 *       implementation, and never shared between calls.</li>
 *   <li>Never return null, never expose bytes through any other channel
 *       (no logging, no domain fields, no API bodies, no exceptions), and
 *       never fabricate content for a run that has none stored.</li>
 * </ul>
 */
public interface SanitizationArtifactStore {

    /**
     * Stores (or replaces) the sanitized output of an owned run.
     *
     * @param ownerSubject calling owner, never blank; must own the run
     * @param runId run the output belongs to, must belong to the owner
     * @param output sanitized bytes to store, never null; read but never
     *        closed here
     */
    void storeArtifact(String ownerSubject, UUID runId, InputStream output);

    /**
     * Opens the stored sanitized output of an owned run.
     *
     * @param ownerSubject calling owner, never blank
     * @param runId run whose output to read, must belong to the owner
     * @return a fresh caller-owned stream, never null
     */
    InputStream openArtifact(String ownerSubject, UUID runId);
}
