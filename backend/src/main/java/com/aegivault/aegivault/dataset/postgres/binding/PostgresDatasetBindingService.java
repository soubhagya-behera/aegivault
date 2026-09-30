package com.aegivault.aegivault.dataset.postgres.binding;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresIdentifiers;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped binding of an Aegivault dataset to one discovered PostgreSQL base
 * table. This milestone stores the reference only: it names the source table,
 * verifies that table really exists through the existing metadata boundary, and
 * does nothing else with it.
 *
 * <p><strong>What this service deliberately does not do.</strong> It never
 * streams rows, profiles, sanitizes, copies data, creates CSV, creates
 * artifacts, or creates a sanitization run. It stores a name, not content. The
 * row-streaming and profiling capabilities sit beside it and are connected to it
 * in a later milestone, once there is a source-management API to decide what a
 * caller may ask for.
 *
 * <p><strong>Owner-scoping is the invariant.</strong> The chain is
 * {@code ownerSubject -> owned dataset -> owned binding}: the dataset is loaded
 * with an owner-scoped query, and the binding is written and read against that
 * owner. A dataset owned by somebody else is indistinguishable from one that
 * does not exist, and a binding belonging to another owner is likewise
 * indistinguishable from no binding — a foreign read never leaks the schema or
 * table name.
 *
 * <p><strong>Existence is verified through metadata, never by query.</strong>
 * The requested table is confirmed with {@link PostgresSchemaDiscoveryService},
 * the same boundary that discovered it in the first place, so a view, a
 * materialized view, or a name that does not exist simply is not a discovered
 * base table and is refused. No SQL is built anywhere in this class, and no row
 * is read.
 *
 * <p><strong>Credentials stay out of the database.</strong> The binding persists
 * only an owner, a schema, and a table. The source's host, port, database,
 * username, password, and URL are never written here; the connection always
 * comes from the configured {@link PostgresDataSource}.
 */
@Service
public class PostgresDatasetBindingService {

    private final PostgresDatasetBindingRepository bindings;

    private final DatasetRepository datasets;

    private final PostgresSchemaDiscoveryService discovery;

    private final ObjectProvider<PostgresDataSource> source;

    public PostgresDatasetBindingService(
            PostgresDatasetBindingRepository bindings,
            DatasetRepository datasets,
            PostgresSchemaDiscoveryService discovery,
            ObjectProvider<PostgresDataSource> source) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.datasets = Objects.requireNonNull(datasets, "datasets must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    /**
     * Binds an owned dataset to one PostgreSQL base table, after confirming the
     * table is a discovered base table of the configured source.
     *
     * <p>The identifiers are validated before the source is contacted, so an
     * unusable name costs nothing and cannot be used to probe what the source
     * contains.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only); must
     *                     own the dataset
     * @param datasetId    dataset to bind, never null
     * @param schemaName   source schema, never blank; must be a plain identifier
     * @param tableName    base table name, never blank; must be a plain identifier
     * @return the stored binding
     * @throws IllegalArgumentException when the owner, schema, or table is blank
     *         or not a plain identifier, or the dataset is null
     * @throws DatasetNotFoundException when the dataset is missing or belongs to
     *         another owner (identical either way)
     * @throws PostgresDatasetAlreadyBoundException when the dataset already has
     *         a binding
     * @throws PostgresDatasetBindingSourceException when the source is not
     *         configured, or the table is not a discovered base table it can see
     */
    @Transactional
    public PostgresDatasetBinding bind(
            String ownerSubject, UUID datasetId, String schemaName, String tableName) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        // Validate both identifiers locally, with the same strict grammar the
        // source layer uses, before the source is contacted at all.
        String schema = PostgresIdentifiers.requireSchemaName(schemaName);
        String table = PostgresIdentifiers.requireTableName(tableName);

        // Confirm ownership before anything else, so a cross-owner caller learns
        // nothing about the source.
        datasets.findByIdAndOwnerSubject(datasetId, owner).orElseThrow(DatasetNotFoundException::new);
        if (bindings.findByDatasetIdAndOwnerSubject(datasetId, owner).isPresent()) {
            throw new PostgresDatasetAlreadyBoundException();
        }
        // Confirm the table really is a discovered base table before storing it.
        verifyBaseTable(schema, table);

        return bindings.save(new PostgresDatasetBinding(datasetId, owner, schema, table));
    }

    /**
     * Reads the binding of an owned dataset.
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId    dataset to read, never null
     * @return the stored binding
     * @throws IllegalArgumentException when the owner is blank or the dataset is
     *         null
     * @throws PostgresDatasetBindingNotFoundException when the dataset is missing,
     *         belongs to another owner, or has no binding yet (identical either
     *         way)
     */
    @Transactional(readOnly = true)
    public PostgresDatasetBinding get(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        return bindings.findByDatasetIdAndOwnerSubject(datasetId, owner)
                .orElseThrow(PostgresDatasetBindingNotFoundException::new);
    }

    /**
     * Removes the binding of an owned dataset, leaving the dataset itself and
     * the source table untouched.
     *
     * <p>Deleting the dataset also removes its binding through the database
     * cascade, so a binding never outlives what it describes.
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId    dataset to unbind, never null
     * @throws IllegalArgumentException when the owner is blank or the dataset is
     *         null
     * @throws PostgresDatasetBindingNotFoundException when there is no binding for
     *         this owner and dataset (identical to a foreign one)
     */
    @Transactional
    public void delete(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        PostgresDatasetBinding binding = bindings.findByDatasetIdAndOwnerSubject(datasetId, owner)
                .orElseThrow(PostgresDatasetBindingNotFoundException::new);
        bindings.delete(binding);
    }

    /**
     * Confirms the table is a discovered base table of the configured source.
     *
     * <p>Existence is checked through the metadata boundary, so this proves the
     * table is a real base table visible to the configured read-only source and
     * nothing more: it reads no row and builds no SQL. A source that is absent,
     * or a table discovery does not return, both surface as the same safe
     * {@link PostgresDatasetBindingSourceException} so binding cannot be used to
     * enumerate the source.
     */
    private void verifyBaseTable(String schemaName, String tableName) {
        PostgresDataSource configured = source.getIfAvailable();
        if (configured == null) {
            throw new PostgresDatasetBindingSourceException(
                    new IllegalStateException("no PostgreSQL source is configured"));
        }
        // The source has exactly one configured schema and no cross-schema
        // browsing, so a binding to any other schema is refused rather than
        // quietly redirected to another schema's same-named table.
        if (!configured.schemaName().equals(schemaName)) {
            throw new PostgresDatasetBindingSourceException(
                    new IllegalStateException("requested schema is not the configured source schema"));
        }
        PostgresTable discovered;
        try {
            discovered = discovery.discover(configured).table(tableName).orElse(null);
        } catch (PostgresSourceConnectionException ex) {
            // Re-thrown as the safe binding failure so a caller cannot tell an
            // unreachable source from an absent table through this message; the
            // cause is kept for server-side diagnostics only.
            throw new PostgresDatasetBindingSourceException(ex);
        }
        if (discovered == null) {
            throw new PostgresDatasetBindingSourceException(
                    new IllegalStateException("no discovered base table for the requested binding"));
        }
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
