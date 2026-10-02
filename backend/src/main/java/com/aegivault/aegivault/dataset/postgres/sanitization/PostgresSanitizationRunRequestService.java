package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.policy.PolicyNotFoundException;
import com.aegivault.aegivault.sanitization.policy.PolicyResponse;
import com.aegivault.aegivault.sanitization.policy.SanitizationPolicyService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLaunchException;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLauncher;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Queues and launches one PostgreSQL sanitization run for an authenticated
 * owner, in the order the API requires: ownership, then binding, then policy,
 * then the run, then the asynchronous launch.
 *
 * <p><strong>This is orchestration only; the real work is already done.</strong>
 * It holds no transformation registry, no row source, no artifact store, and no
 * audit ledger. {@link PostgresSanitizationRunService} still owns run creation,
 * {@link SanitizationRunService} still owns persistence and the frozen
 * {@code PolicySnapshot}, and {@link SanitizationRunJobLauncher} remains the
 * single launcher and the single bounded pool. Nothing here re-implements a
 * lifecycle transition or bypasses the executor's {@code startRun} gate.
 *
 * <p><strong>The check order is the security-relevant part.</strong> Ownership
 * and the binding are resolved before the policy, so a foreign caller learns
 * nothing about its own or anyone else's policies: the owner-scoped policy read
 * is only reached once the dataset and its binding are known to be the caller's.
 * All three are owner-scoped lookups, so a missing resource and another owner's
 * resource are indistinguishable.
 *
 * <p><strong>The policy is resolved once, here, and never again.</strong> The
 * caller names a policy id; the labels and rules are read server-side and frozen
 * into the run's immutable snapshot at creation. The background worker never
 * re-resolves a policy, so a later policy edit or deletion cannot change a run
 * that is already queued.
 *
 * <p><strong>Nothing is read from PostgreSQL during the request.</strong> The
 * source is only touched later, by the worker. The binding is resolved here to
 * prove the dataset is usable, not to read rows, and the binding's schema and
 * table are never copied into the run, the request, or the response.
 *
 * <p><strong>Launch failure is reported, not disguised.</strong> If submission
 * fails, the launcher's {@link SanitizationRunJobLaunchException} propagates and
 * this method does not pretend the run is executing. The run keeps its
 * {@code QUEUED} state and remains launchable, which is the launcher's own
 * documented behaviour; this service does not mark it running, fail it, retry
 * it, or delete it.
 */
@Service
public class PostgresSanitizationRunRequestService {

    private final PostgresSanitizationRunService postgresRuns;

    private final PostgresDatasetBindingService bindings;

    private final SanitizationPolicyService policies;

    private final SanitizationRunService runs;

    private final SanitizationRunJobLauncher launcher;

    public PostgresSanitizationRunRequestService(
            PostgresSanitizationRunService postgresRuns,
            PostgresDatasetBindingService bindings,
            SanitizationPolicyService policies,
            SanitizationRunService runs,
            SanitizationRunJobLauncher launcher) {
        this.postgresRuns = Objects.requireNonNull(postgresRuns, "postgresRuns must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
    }

    /**
     * Creates one {@code POSTGRESQL} {@code QUEUED} run for an owned, bound
     * dataset and hands it to the existing background launcher.
     *
     * <p>Returns as soon as the run is persisted and submitted. Sanitization
     * itself has not happened yet and may still be running, which is why the
     * status is reported as {@code QUEUED}.
     *
     * @param ownerSubject authenticated owner from the verified token, never
     *        blank; must own the dataset, the binding, and the policy
     * @param datasetId bound dataset to sanitize, never null
     * @param policyId caller's persisted policy to freeze into the run, never
     *        null; never taken as a plan or as rules
     * @return the persisted queued run, metadata only
     * @throws PostgresDatasetBindingNotFoundException when the dataset is
     *         missing, belongs to another owner, or has no PostgreSQL binding
     *         (identical either way)
     * @throws PolicyNotFoundException when the policy is missing or belongs to
     *         another owner
     * @throws com.aegivault.aegivault.sanitization.run.ReferencedDatasetNotFoundException
     *         when the dataset is not the owner's
     * @throws SanitizationRunJobLaunchException when the bounded pool refuses
     *         the submission; the run remains queued and unchanged
     */
    public SanitizationRunView queueAndLaunch(
            String ownerSubject, UUID datasetId, UUID policyId) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(policyId, "policyId must not be null");

        // The dataset, its binding, and its owner are resolved first, in that
        // order: the binding must already exist and be the caller's, so this
        // endpoint never discovers a table, selects the first table, or creates
        // a binding. Only once the dataset is known to be the caller's is its
        // policy read at all, so a foreign caller never reaches the policy
        // table and cannot learn whether a policy id exists.
        bindings.get(ownerSubject, datasetId);
        SanitizationRunView queued = queueFromOwnedPolicy(ownerSubject, datasetId, policyId);

        // Submitted only after the run is persisted. launch() reads the run row
        // for itself, so the worker acts with the owner recorded at creation and
        // never with a caller-supplied owner.
        launcher.launch(runs.loadForExecution(queued.id()));
        return queued;
    }

    /**
     * Resolves the caller's policy and queues the run, freezing that policy's
     * labels and rules into the run at creation.
     *
     * <p>Split out so the ordering above reads as one step. The plan is built
     * here from persisted rules and is never accepted from the request.
     */
    private SanitizationRunView queueFromOwnedPolicy(
            String ownerSubject, UUID datasetId, UUID policyId) {
        PolicyResponse policy = policies.get(ownerSubject, policyId);
        return postgresRuns.queue(
                ownerSubject,
                datasetId,
                TransformationPlan.of(policy.rules()),
                policy.name(),
                policy.version());
    }
}