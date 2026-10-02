package com.aegivault.aegivault.dataset.postgres.binding;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of one PostgreSQL dataset binding: which dataset, which schema, which
 * table, and when the record was written.
 *
 * <p><strong>Metadata only.</strong> The four fields are exactly what the binding
 * stores. There is no {@code ownerSubject} (an internal authorization field that
 * the caller already embodies), no host, port, database, username, password, or
 * JDBC URL — those remain application configuration and are not persisted
 * anywhere — and no row count, sample value, PII finding, or SQL. The shape is
 * the guarantee: none of those have a field to occupy.
 *
 * <p><strong>It says nothing about the dataset's source type.</strong> Binding a
 * PostgreSQL table does not change {@code Dataset.source_type}, which still
 * records CSV; this view therefore reports the association and the row's own
 * timestamps without implying which source the dataset is ultimately read from.
 * That remains an explicit source-management decision.
 *
 * @param datasetId the bound dataset, never null
 * @param schemaName source schema, never blank
 * @param tableName bound base table name, never blank
 * @param createdAt when the binding row was written, never null
 * @param updatedAt when the binding row was last written, never null
 */
public record PostgresBindingResponse(
        UUID datasetId,
        String schemaName,
        String tableName,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Renders a stored binding for the API.
     *
     * @param binding the persisted binding
     * @return the response, never null
     */
    static PostgresBindingResponse from(PostgresDatasetBinding binding) {
        return new PostgresBindingResponse(
                binding.getDatasetId(),
                binding.getSchemaName(),
                binding.getTableName(),
                binding.getCreatedAt(),
                binding.getUpdatedAt());
    }
}