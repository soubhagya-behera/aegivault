package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped run lifecycle operations. The invariants are:
 *
 * <ul>
 *   <li>{@code ownerSubject -> owner-scoped repository query} on every
 *       method: USER and ADMIN behave identically, no cross-user access
 *       exists here, and missing ids are indistinguishable from other
 *       owners' ids.</li>
 *   <li>A run is always created against a dataset the caller owns; the
 *       owner is copied onto the run row so later lookups need no join.</li>
 *   <li>Status changes go through the entity's {@link RunStatus} state
 *       machine; this service never sets a status field directly.</li>
 *   <li>The policy is frozen at creation into an immutable
 *       {@link PolicySnapshot}; later policy changes cannot mutate a
 *       persisted run.</li>
 * </ul>
 *
 * <p>Each method is one transaction, and each write flushes before
 * returning so the returned view (including the optimistic-locking
 * version) reflects the persisted row. No distributed transactions are
 * claimed: there is a single database.
 *
 * <p>This service owns persistence and lifecycle only. It never invokes
 * the CSV sanitization engine: orchestration lives in
 * {@link SanitizationRunExecutor} (synchronous success path today, a
 * background worker later), which calls the lifecycle methods here around
 * the existing engine.
 */
@Service
@RequiredArgsConstructor
public class SanitizationRunService {

    private final SanitizationRunRepository runs;

    private final DatasetRepository datasets;

    /**
     * Creates a {@code QUEUED} run against an owned dataset, freezing the
     * given plan into the run row.
     *
     * @param ownerSubject calling owner, never blank
     * @param datasetId dataset to sanitize, must belong to the owner
     * @param plan explicit plan to freeze, never null
     * @param policyName policy label, e.g. {@code "default"}, never blank
     * @param policyVersion version label, e.g. {@code "v1"}, never blank
     * @return the persisted run
     * @throws ReferencedDatasetNotFoundException when the dataset is missing
     *         or belongs to another owner
     */
    @Transactional
    public SanitizationRunView createRun(
            String ownerSubject, UUID datasetId, TransformationPlan plan, String policyName, String policyVersion) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Dataset dataset = datasets
                .findByIdAndOwnerSubject(datasetId, owner)
                .orElseThrow(ReferencedDatasetNotFoundException::new);
        PolicySnapshot snapshot = PolicySnapshot.fromPlan(policyName, policyVersion, plan);
        return SanitizationRunView.from(runs.saveAndFlush(new SanitizationRun(dataset, owner, snapshot)));
    }

    /**
     * Reads one owned run.
     *
     * @throws SanitizationRunNotFoundException when the run is missing or
     *         belongs to another owner
     */
    @Transactional(readOnly = true)
    public SanitizationRunView get(String ownerSubject, UUID runId) {
        return SanitizationRunView.from(loadOwned(ownerSubject, runId));
    }

    /**
     * Lists the caller's runs against one owned dataset.
     */
    @Transactional(readOnly = true)
    public List<SanitizationRunView> listByDataset(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        return runs.findByDatasetIdAndOwnerSubject(datasetId, owner).stream()
                .map(SanitizationRunView::from)
                .toList();
    }

    /**
     * Moves a {@code QUEUED} run to {@code RUNNING}.
     *
     * @throws SanitizationRunNotFoundException when the run is missing or
     *         belongs to another owner
     * @throws InvalidRunTransitionException when the run is not QUEUED
     */
    @Transactional
    public SanitizationRunView startRun(String ownerSubject, UUID runId) {
        SanitizationRun run = loadOwned(ownerSubject, runId);
        run.markRunning();
        return SanitizationRunView.from(runs.saveAndFlush(run));
    }

    /**
     * Moves a {@code RUNNING} run to {@code COMPLETED} with structural
     * result counts.
     *
     * @param result counts only, never null
     * @throws SanitizationRunNotFoundException when the run is missing or
     *         belongs to another owner
     * @throws InvalidRunTransitionException when the run is not RUNNING
     */
    @Transactional
    public SanitizationRunView completeRun(String ownerSubject, UUID runId, RunResult result) {
        SanitizationRun run = loadOwned(ownerSubject, runId);
        run.markCompleted(result);
        return SanitizationRunView.from(runs.saveAndFlush(run));
    }

    /**
     * Moves a {@code RUNNING} run to {@code FAILED} with safe structured
     * failure metadata.
     *
     * @param failure metadata-only failure, never null and never a
     *        {@link Throwable}: stack traces cannot reach this method
     * @throws SanitizationRunNotFoundException when the run is missing or
     *         belongs to another owner
     * @throws InvalidRunTransitionException when the run is not RUNNING
     */
    @Transactional
    public SanitizationRunView failRun(String ownerSubject, UUID runId, RunFailure failure) {
        SanitizationRun run = loadOwned(ownerSubject, runId);
        run.markFailed(failure);
        return SanitizationRunView.from(runs.saveAndFlush(run));
    }

    private SanitizationRun loadOwned(String ownerSubject, UUID runId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(runId, "runId must not be null");
        return runs.findByIdAndOwnerSubject(runId, owner)
                .orElseThrow(SanitizationRunNotFoundException::new);
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
