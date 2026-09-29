package com.aegivault.aegivault.dataset.postgres;

import java.util.List;
import java.util.Objects;

/**
 * The safe metadata result of discovering one PostgreSQL source schema: the
 * schema's name and its base tables with their columns.
 *
 * <p><strong>Metadata only, by construction.</strong> Every type reachable from
 * here carries names, positions, and type names — no cell value, no sample row,
 * no row count, no secret, and no connection detail has anywhere to live in this
 * shape. Discovering a schema therefore cannot copy production data, because the
 * result has no field to copy it into.
 *
 * <p>An empty schema is a normal outcome, not an error: a schema with no base
 * tables produces an empty table list, so a caller can tell "nothing to
 * discover" apart from "discovery failed" — the latter arrives as
 * {@link PostgresSchemaDiscoveryException} instead of an empty result.
 *
 * @param schemaName the schema that was inspected, never blank
 * @param tables discovered tables, never null, possibly empty
 */
public record PostgresSchema(String schemaName, List<PostgresTable> tables) {

    public PostgresSchema {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("schemaName must not be blank");
        }
        Objects.requireNonNull(tables, "tables must not be null");
        tables = List.copyOf(tables);
    }

    /**
     * Number of discovered tables.
     *
     * @return the size of {@link #tables()}
     */
    public int tableCount() {
        return tables.size();
    }

    /**
     * Finds one discovered table by name.
     *
     * @param name table name to look for, never blank
     * @return the table, or empty when this schema has no such table
     */
    public java.util.Optional<PostgresTable> table(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return tables.stream().filter(table -> table.name().equals(name)).findFirst();
    }
}
