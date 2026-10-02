package com.aegivault.aegivault.dataset.postgres.binding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Binding payload for {@code POST /api/datasets/{datasetId}/postgres/binding}:
 * the schema and table to associate with the dataset named in the path.
 *
 * <p><strong>Two fields, nothing else.</strong> There is deliberately no
 * {@code ownerSubject} — ownership comes from the verified JWT subject and an
 * attempted {@code ownerSubject} property is not bound and never used — and no
 * host, port, database, username, password, JDBC URL, SQL, source configuration,
 * row limit, or policy field. The caller chooses a table it has already
 * discovered; it cannot point the application at a different database or supply
 * credentials.
 *
 * <p>The bounds are a fast rejection at the HTTP edge, mirroring the internal
 * grammar rather than replacing it: PostgreSQL truncates identifiers beyond 63
 * characters, so anything longer can never name a real object. The service still
 * validates both identifiers against the strict grammar itself as defense in
 * depth, so a caller reaching it by another route gets the same answer.
 *
 * <p>Expected JSON shape:
 *
 * <pre>
 * {
 *   "schemaName": "public",
 *   "tableName": "customers"
 * }
 * </pre>
 *
 * @param schemaName source schema holding the table, never blank, at most 63
 *        characters
 * @param tableName discovered base table name, never blank, at most 63 characters
 */
public record CreatePostgresBindingRequest(
        @NotBlank @Size(max = 63) String schemaName,
        @NotBlank @Size(max = 63) String tableName) {}