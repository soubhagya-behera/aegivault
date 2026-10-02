package com.aegivault.aegivault.dataset.postgres.discovery;

import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryException;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Lists the base tables an owner may choose from, by discovering the configured
 * PostgreSQL schema on their behalf.
 *
 * <p><strong>Discovery only.</strong> This service verifies that the caller owns
 * the dataset, then returns schema metadata. It does not bind a table, profile
 * one, sanitize one, queue a run, create an artifact, or read a single row — it
 * holds no collaborator capable of any of that, so those capabilities cannot be
 * reached from here even by mistake. Choosing a table is an explicit later step.
 *
 * <p><strong>Ownership is checked first and owner-scoped.</strong> The dataset is
 * loaded with the owner-scoped repository query, so a foreign dataset and a
 * missing one are indistinguishable: both raise
 * {@link com.aegivault.aegivault.dataset.DatasetNotFoundException} before the
 * source is touched. There is no ADMIN bypass — the only owner this accepts is
 * the authenticated subject, and a caller cannot supply a different one.
 *
 * <p><strong>Nothing is opened here.</strong> The configured
 * {@link PostgresDataSource} and the existing
 * {@link PostgresSchemaDiscoveryService} do all the work; this service never
 * touches JDBC, never builds a statement, and cannot see a credential. Both are
 * optional beans, so a checkout with no source configured is a clean
 * source-unavailable answer rather than a startup failure.
 *
 * <p><strong>Determinism is the discovery model's.</strong> Table and column
 * order come straight from the discovery service — ordinal order for columns —
 * because that is the same ordering the row stream and profiler already rely on.
 * No sorting is applied here that could disagree with it.
 */
@Service
public class PostgresDatasetTableDiscoveryService {

    private final DatasetRepository datasets;

    private final PostgresSchemaDiscoveryService discovery;

    /** Optional: absent when no PostgreSQL source is configured. */
    private final ObjectProvider<PostgresDataSource> source;

    public PostgresDatasetTableDiscoveryService(
            DatasetRepository datasets,
            PostgresSchemaDiscoveryService discovery,
            ObjectProvider<PostgresDataSource> source) {
        this.datasets = Objects.requireNonNull(datasets, "datasets must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    /**
     * Returns the configured schema's base tables for an owned dataset.
     *
     * <p>An empty schema is a normal answer, not an error: the configured schema
     * is returned with an empty table list.
     *
     * @param ownerSubject authenticated owner, never blank; must own the dataset
     * @param datasetId dataset establishing context, never null
     * @return the discovered schema and its tables, never null
     * @throws IllegalArgumentException when the owner is blank
     * @throws com.aegivault.aegivault.dataset.DatasetNotFoundException when the
     *         dataset is missing or belongs to another owner (identical either
     *         way)
     * @throws PostgresSourceUnavailableException when no PostgreSQL source is
     *         configured
     * @throws PostgresSchemaDiscoveryUnavailableException when the configured
     *         source could not be reached or its metadata could not be read
     */
    public PostgresTablesResponse listTables(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        // Ownership first: an unauthorised caller learns nothing about the source,
        // not even whether one is configured.
        datasets.findByIdAndOwnerSubject(datasetId, owner)
                .orElseThrow(com.aegivault.aegivault.dataset.DatasetNotFoundException::new);

        PostgresDataSource configured = source.getIfAvailable();
        if (configured == null) {
            throw new PostgresSourceUnavailableException();
        }
        return PostgresTablesResponse.from(discover(configured));
    }

    /** Runs the existing discovery, mapping its failures to the safe API errors. */
    private com.aegivault.aegivault.dataset.postgres.PostgresSchema discover(PostgresDataSource configured) {
        try {
            return discovery.discover(configured);
        } catch (PostgresSourceConnectionException | PostgresSchemaDiscoveryException ex) {
            // Both are already documented-safe with fixed messages; mapping them to
            // one API failure keeps the web layer to a single status for "the
            // source could not be read" and guarantees no driver, SQL, or
            // connection detail is surfaced. Only these two are caught, so a
            // programming error still propagates instead of being hidden.
            throw new PostgresSchemaDiscoveryUnavailableException();
        }
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}