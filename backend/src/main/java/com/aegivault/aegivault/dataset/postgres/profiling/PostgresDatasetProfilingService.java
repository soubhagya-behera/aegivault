package com.aegivault.aegivault.dataset.postgres.profiling;

import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryException;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import com.aegivault.aegivault.dataset.postgres.PostgresTableProfileException;
import com.aegivault.aegivault.dataset.postgres.PostgresTableProfiler;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRowReadException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.dataset.profile.DatasetProfileResponse;
import com.aegivault.aegivault.dataset.profile.DatasetProfileService;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Profiles the PostgreSQL table an owned dataset is bound to, and persists the
 * result through the existing profile service. This is orchestration only: it
 * decides <em>which</em> table to profile, and every fact in the resulting
 * profile is produced by code that already existed.
 *
 * <p><strong>The flow, and nothing else.</strong>
 *
 * <pre>
 *   ownerSubject + datasetId
 *       -&gt; PostgresDatasetBindingService.get   (owner-scoped binding)
 *       -&gt; PostgresSchemaDiscoveryService.discover
 *       -&gt; the bound table, re-confirmed as a discovered base table
 *       -&gt; PostgresTableProfiler.profile      (bounded stream + existing PII engine)
 *       -&gt; DatasetProfileService.saveProfile   (existing replace-semantics persistence)
 *       -&gt; DatasetProfileService.getProfile    (the persisted state, re-read)
 * </pre>
 *
 * <p><strong>Nothing is re-implemented here.</strong> Detection, counting,
 * rates, and column ordering are the existing PII engine's, reached only through
 * {@link PostgresTableProfiler}; persistence is the existing
 * {@link DatasetProfileService}, which already owns owner scoping and
 * replace-on-resave semantics; existence checking is the existing
 * {@link PostgresSchemaDiscoveryService}. This class adds no profile table, no
 * PostgreSQL-specific profile model, and no second profile schema, so a stored
 * PostgreSQL profile is indistinguishable in shape from a stored CSV profile.
 *
 * <p><strong>The binding is authoritative and is never bypassed.</strong> The
 * table profiled is exactly the name the owner bound, resolved through metadata
 * discovery. There is no caller-supplied table, no "closest match", no prefix or
 * pattern search, and no fallback to another table or schema: if the bound name
 * is not a discovered base table, profiling fails.
 *
 * <p><strong>Stale bindings fail safely and are left alone.</strong> A binding
 * can outlive its table, because the binding is stored here while the table
 * lives in an external database. When the bound table has been dropped, renamed,
 * or replaced by a view, the name simply stops being a discovered base table and
 * this service raises {@link PostgresDatasetProfilingException}. It
 * deliberately does <em>not</em> delete or repair the binding, does not rebind to
 * anything else, and does not persist a partial profile — repairing a stale
 * binding is an explicit owner decision for the source-management API that does
 * not exist yet, not something an implicit failure path should do. Because the
 * profile is only saved after profiling has fully succeeded, a failure at any
 * step leaves whatever profile already existed untouched.
 *
 * <p><strong>What "profiled" means is the existing engine's meaning.</strong>
 * The profile covers the rows the bounded row stream actually delivered, up to
 * its configured row ceiling — never the whole table. Nothing here widens or
 * claims to exceed that bound, and no persisted field asserts a row count that
 * was not observed.
 *
 * <p><strong>Metadata only, end to end.</strong> Row values exist inside the
 * profiler for the duration of its call. This class never logs a value, never
 * places one in an exception, never returns one, and hands the profiler's result
 * to the profile service unchanged. {@link DatasetProfileResponse} carries column
 * names, counts, rates, and type names only.
 *
 * <p><strong>Ownership is threaded, never widened.</strong> The authenticated
 * {@code ownerSubject} is an explicit argument, trimmed once, rejected when
 * blank, and passed to every composed call. The binding lookup, the dataset
 * lookup inside the profile service, and the profile read are all owner-scoped,
 * and there is no ADMIN bypass: a foreign dataset or binding is
 * indistinguishable from a missing one.
 *
 * <p><strong>No transaction is opened here.</strong> As in the CSV path, each
 * composed call commits in its own transaction: the source read and the PII
 * analysis run outside any database transaction, and the save is one atomic
 * write. That is what makes "no partial profile on failure" structural rather
 * than a property of exception handling.
 *
 * <p><strong>Dependency direction is one-way and narrow:</strong> the binding
 * service, the discovery service, the profiler, the optional source, and the
 * profile service. No sanitizer, sanitization run, artifact store, policy
 * resolver or evaluator, token budget, rate limiter, gateway, Redis, audit
 * ledger, or controller.
 */
@Service
public class PostgresDatasetProfilingService {
    private final PostgresDatasetBindingService bindings;

    private final PostgresSchemaDiscoveryService discovery;

    private final DatasetProfileService profiles;

    /** Optional: absent when no PostgreSQL source is configured. */
    private final ObjectProvider<PostgresDataSource> source;

    /** Optional: absent when no PostgreSQL source is configured. */
    private final ObjectProvider<PostgresTableProfiler> profilers;

    public PostgresDatasetProfilingService(
            PostgresDatasetBindingService bindings,
            PostgresSchemaDiscoveryService discovery,
            DatasetProfileService profiles,
            ObjectProvider<PostgresDataSource> source,
            ObjectProvider<PostgresTableProfiler> profilers) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.profilers = Objects.requireNonNull(profilers, "profilers must not be null");
    }
    /**
     * Profiles the table bound to an owned dataset and persists the result,
     * returning the persisted view.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only);
     *        must own the dataset and its binding
     * @param datasetId dataset to profile, never null
     * @return the persisted profile view; column metadata, counts, and rates
     *         only
     * @throws IllegalArgumentException when the owner is blank or the dataset id
     *         is null
     * @throws com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException
     *         when the dataset is missing, belongs to another owner, or has no
     *         PostgreSQL binding yet (identical either way, and silent about
     *         the source)
     * @throws PostgresDatasetProfilingException when the source is not
     *         configured, the bound table is no longer a discovered base table,
     *         discovery fails, or profiling fails; one fixed safe message in
     *         every case
     */
    public DatasetProfileResponse profile(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");

        // The binding is the only source of the table name, and the lookup is
        // owner-scoped: a foreign or absent binding raises the binding layer's
        // own not-found signal, which never mentions the source.
        PostgresDatasetBinding binding = bindings.get(owner, datasetId);

        // Profiling never runs without both halves of the source configuration:
        // a checkout with no PostgreSQL source has no profiler and no source,
        // and must fail safely here rather than at startup.
        PostgresDataSource configured = source.getIfAvailable();
        PostgresTableProfiler profiler = profilers.getIfAvailable();
        if (configured == null || profiler == null) {
            throw new PostgresDatasetProfilingException(
                    new IllegalStateException("no PostgreSQL source is configured"));
        }

        // Re-confirmed through the same metadata boundary that verified the
        // binding at creation time. A table that has since been dropped or
        // replaced by a view is no longer a discovered base table, so profiling
        // stops here: no substitution, no partial profile, no repair.
        PostgresTable table = discoverBoundTable(configured, binding);

        DatasetProfile profile;
        try {
            profile = profiler.profile(datasetId, configured, table);
        } catch (PostgresSourceConnectionException | PostgresTableRowReadException
                | PostgresTableProfileException ex) {
            // Each source-layer signal is already safe, but collapsing them into
            // one fixed message here keeps a caller from distinguishing "gone"
            // from "unreachable" from "analysis failed" by exception type.
            throw new PostgresDatasetProfilingException(ex);
        }

        // Reached only on full success, so the profile service's replace
        // semantics can only ever replace a good profile with a good profile.
        profiles.saveProfile(owner, datasetId, profile);
        return profiles.getProfile(owner, datasetId);
    }
    /** Resolves the bound table through metadata discovery, or fails safely. */
    private PostgresTable discoverBoundTable(
            PostgresDataSource configured, PostgresDatasetBinding binding) {
        // The source has exactly one configured schema. A binding recorded for a
        // different schema cannot be honoured without either silently profiling
        // another schema or browsing across schemas, so it fails instead.
        if (!configured.schemaName().equals(binding.getSchemaName())) {
            throw new PostgresDatasetProfilingException(
                    new IllegalStateException("bound schema is not the configured source schema"));
        }
        try {
            return discovery.discover(configured)
                    .table(binding.getTableName())
                    // Empty means the bound table is gone or is no longer a base
                    // table. The binding is intentionally left exactly as it is.
                    .orElseThrow(() -> new PostgresDatasetProfilingException(
                            new IllegalStateException("bound table is no longer a discovered base table")));
        } catch (PostgresSourceConnectionException | PostgresSchemaDiscoveryException ex) {
            throw new PostgresDatasetProfilingException(ex);
        }
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}