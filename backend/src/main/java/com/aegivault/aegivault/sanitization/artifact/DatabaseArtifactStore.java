package com.aegivault.aegivault.sanitization.artifact;

import com.aegivault.aegivault.dataset.csv.CsvLimits;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRun;
import com.aegivault.aegivault.sanitization.run.SanitizationRunNotFoundException;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-BYTEA {@link SanitizationArtifactStore}: stores and serves one
 * run's sanitized CSV output.
 *
 * <p>Bounds: {@link #MAX_ARTIFACT_BYTES} (40 MiB = 4x the 10 MiB input
 * bound) — a separate explicit limit, because fixed-size substitutions
 * (33-char synthetic emails, 64-char hashes) expand short detected values
 * several-fold and reusing the input limit would wrongly reject legitimate
 * hash-heavy output. Oversized output is rejected while capturing, before
 * any row is persisted; nothing is truncated and no partial artifact is
 * ever stored. The bound covers realistic substitution expansion with
 * headroom; denser extremes fail closed instead.
 *
 * <p>Each opened stream is a fresh {@link ByteArrayInputStream} over an
 * independent copy of the stored bytes: callers can read and close freely,
 * and closing never affects the caller (nothing caller-owned is ever
 * closed here) or the persistence context. Run ownership is verified
 * through the existing owner-scoped run lookup on store; reads predicate
 * on the artifact row's own owner, so missing runs, foreign runs, and
 * missing artifacts behave identically.
 *
 * <p>Reads additionally require the run to be {@code COMPLETED}, so a
 * stored artifact is servable only for a finished run — a second
 * enforcement point for the executor's "completed run has exactly one
 * artifact, failed run has none" invariant, this time on the read path.
 * Every refusal (missing run, foreign run, unfinished run, missing
 * artifact) is the same {@link SanitizationRunNotFoundException} with the
 * same message, so a caller cannot learn which of those is true. Writes
 * stay ungated: the executor stores the artifact before it completes the
 * run.
 */
@Service
@RequiredArgsConstructor
public class DatabaseArtifactStore implements SanitizationArtifactStore {

    /**
     * Maximum sanitized output accepted for one run: 4x the 10 MiB CSV
     * input bound. Separate from the input limit on purpose (see above);
     * mirrored by the schema CHECK, so one bound has two enforcement
     * points.
     */
    public static final long MAX_ARTIFACT_BYTES = 4L * CsvLimits.DEFAULT_MAX_INPUT_BYTES;

    private final SanitizationRunRepository runs;

    private final SanitizationArtifactRepository artifacts;

    @Override
    @Transactional
    public void storeArtifact(String ownerSubject, UUID runId, InputStream output) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(output, "output must not be null");
        runs.findByIdAndOwnerSubject(runId, owner).orElseThrow(SanitizationRunNotFoundException::new);
        byte[] content = readBounded(output);
        artifacts
                .findByRunIdAndOwnerSubject(runId, owner)
                .ifPresentOrElse(
                        existing -> existing.replaceContent(content),
                        () -> artifacts.save(new SanitizationArtifact(runId, owner, content)));
    }

    @Override
    @Transactional(readOnly = true)
    public InputStream openArtifact(String ownerSubject, UUID runId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(runId, "runId must not be null");
        SanitizationRun run = runs.findByIdAndOwnerSubject(runId, owner)
                .orElseThrow(SanitizationRunNotFoundException::new);
        if (run.getStatus() != RunStatus.COMPLETED) {
            throw new SanitizationRunNotFoundException();
        }
        SanitizationArtifact stored = artifacts
                .findByRunIdAndOwnerSubject(runId, owner)
                .orElseThrow(SanitizationRunNotFoundException::new);
        return new ByteArrayInputStream(stored.contentCopy());
    }

    private static byte[] readBounded(InputStream output) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = output.read(chunk)) != -1) {
                total += read;
                if (total > MAX_ARTIFACT_BYTES) {
                    throw new ArtifactTooLargeException(MAX_ARTIFACT_BYTES);
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read sanitized output.");
        }
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
