package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.run.SanitizationContentSource;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceProvider;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Creates and serves {@link SanitizationSourceType#POSTGRESQL} sanitization runs.
 *
 * <p><strong>Two small responsibilities, no lifecycle.</strong> It can queue a
 * PostgreSQL-backed run for an owned dataset, and it can hand the executor a
 * content source for one that is already queued. It performs no state
 * transition, stores no artifact, appends no audit event, opens no connection,
 * and holds no thread pool — {@link SanitizationRunExecutor} remains the single
 * lifecycle owner and {@code SanitizationRunJobLauncher} remains the single
 * launcher.
 *
 * <p><strong>Creation checks the binding; execution re-resolves it.</strong> A run
 * is only queued for a dataset that actually has an owner-scoped binding, so an
 * unusable run is never created. That check is not a promise about execution
 * time: the table may disappear afterwards, so
 * {@link #contentFor(String, UUID, TransformationPlan)} re-resolves the binding
 * from the dataset when the worker actually runs.
 *
 * <p><strong>Nothing about the source is stored here.</strong> The run row records
 * only the source <em>kind</em>, its owner, its dataset, and the frozen policy
 * snapshot. No schema, table, host, port, database, username, password, or JDBC
 * URL is written anywhere, and the content source is built from the dataset's
 * persisted binding plus the application's configured datasource.
 *
 * <p><strong>Ownership is owner-scoped throughout, with no ADMIN bypass.</strong>
 * The binding lookup and the run creation both take the authenticated owner, so a
 * caller cannot queue or execute against another actor's dataset or binding.
 *
 * <p><strong>No retries and no recovery.</strong> A queued run that fails stays
 * failed; nothing re-queues it, backs off, or reconciles it.
 */
@Service
public class PostgresSanitizationRunService implements SanitizationSourceProvider {

    private final SanitizationRunService runs;

    private final PostgresDatasetBindingService bindings;

    /**
     * Held lazily, because the sanitization service and the run executor are
     * mutual collaborators by design: the executor dispatches to this provider,
     * and this provider asks the sanitization service for content. Resolving it
     * through a provider breaks the construction cycle without either side
     * having to know the other exists at construction time.
     */
    private final ObjectProvider<PostgresDatasetSanitizationService> sanitization;

    public PostgresSanitizationRunService(
            SanitizationRunService runs,
            PostgresDatasetBindingService bindings,
            ObjectProvider<PostgresDatasetSanitizationService> sanitization) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.sanitization = Objects.requireNonNull(sanitization, "sanitization must not be null");
    }

    @Override
    public SanitizationSourceType sourceType() {
        return SanitizationSourceType.POSTGRESQL;
    }

    /**
     * Queues a PostgreSQL-backed run for an owned, bound dataset.
     *
     * <p>The dataset, plan, and policy labels are frozen into the run at this
     * moment, so a later policy edit or deletion cannot change what this run does.
     * Nothing is executed here: the run stays {@code QUEUED} until the existing
     * launcher submits it.
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId bound dataset to sanitize, never null
     * @param plan explicit plan to freeze into the run, never null; no policy is
     *        selected or inferred here
     * @param policyName policy label to freeze, never blank
     * @param policyVersion policy version label to freeze, never blank
     * @return the persisted queued run
     * @throws IllegalArgumentException when the owner is blank or an argument is
     *         null
     * @throws com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException
     *         when the dataset is missing, belongs to another owner, or has no
     *         PostgreSQL binding (identical either way)
     * @throws com.aegivault.aegivault.sanitization.run.ReferencedDatasetNotFoundException
     *         when the dataset is not the owner's
     */
    public SanitizationRunView queue(
            String ownerSubject,
            UUID datasetId,
            TransformationPlan plan,
            String policyName,
            String policyVersion) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        // Resolved owner-scoped before the run exists, so a dataset with no
        // usable binding never becomes a queued run.
        bindings.get(ownerSubject, datasetId);
        return runs.createRun(
                ownerSubject, datasetId, plan, policyName, policyVersion, SanitizationSourceType.POSTGRESQL);
    }

    /**
     * Hands the executor the content source for one queued PostgreSQL run.
     *
     * <p>The owner and dataset come from the run row, and the plan from the run's
     * own frozen snapshot — never from a caller. Binding resolution and metadata
     * re-confirmation happen lazily inside the returned source, so a table that
     * has since disappeared fails the run through the executor's normal failure
     * mapping with safe metadata and no artifact, and the binding is never
     * modified.
     *
     * @param ownerSubject owner recorded on the run row, never blank
     * @param datasetId dataset recorded on the run row, never null
     * @param plan plan rebuilt from the run's frozen snapshot, never null
     * @return the content source, never null
     */
    @Override
    public SanitizationContentSource contentFor(
            String ownerSubject, UUID datasetId, TransformationPlan plan) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        return requireSanitization().contentSourceFor(ownerSubject, datasetId, plan);
    }

    private PostgresDatasetSanitizationService requireSanitization() {
        PostgresDatasetSanitizationService service = sanitization.getIfAvailable();
        if (service == null) {
            throw new PostgresDatasetSanitizationException(
                    new IllegalStateException("no PostgreSQL sanitization service is available"));
        }
        return service;
    }
}