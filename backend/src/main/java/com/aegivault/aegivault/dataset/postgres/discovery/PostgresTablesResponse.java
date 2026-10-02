package com.aegivault.aegivault.dataset.postgres.discovery;

import com.aegivault.aegivault.dataset.postgres.PostgresColumn;
import com.aegivault.aegivault.dataset.postgres.PostgresSchema;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import java.util.List;

/**
 * API view of the base tables available in the configured PostgreSQL schema:
 * the schema name, and per table its name and columns.
 *
 * <p><strong>Metadata only, structurally.</strong> Every field here is already
 * present on {@link PostgresSchema}, {@link PostgresTable}, and
 * {@link PostgresColumn} — names, ordinal positions, and declared type names.
 * There is no field here for a host, port, database, username, password, JDBC
 * URL, row count, row value, sample, PII finding, index, constraint, default, or
 * comment, so none of those can be returned even by accident: the response shape
 * itself is the guarantee, not a filter applied afterwards.
 *
 * <p><strong>No owner, and no dataset id.</strong> The caller already knows which
 * dataset it asked about and who it is, so neither is echoed back — and in
 * particular {@code ownerSubject} is an internal authorization field that this
 * response deliberately does not carry.
 *
 * <p><strong>Order is the discovery model's, not a new policy.</strong> Tables
 * appear in the order {@link com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService}
 * produced them and columns in discovered {@code ORDINAL_POSITION} order. This
 * type performs no sorting, because a second ordering rule could disagree with
 * the one the profiler and row stream already rely on.
 *
 * @param schema the one configured schema that was inspected, never blank
 * @param tables discovered base tables, never null; empty when the schema has none
 */
public record PostgresTablesResponse(String schema, List<PostgresTableView> tables) {

    public PostgresTablesResponse {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema must not be blank");
        }
        tables = List.copyOf(tables);
    }

    /**
     * Renders a discovered schema for the API.
     *
     * @param discovered schema metadata from the existing discovery service
     * @return the response, never null
     */
    static PostgresTablesResponse from(PostgresSchema discovered) {
        return new PostgresTablesResponse(
                discovered.schemaName(),
                discovered.tables().stream().map(PostgresTableView::from).toList());
    }

    /**
     * One discovered base table and its columns.
     *
     * @param name table name as the source reports it, never blank
     * @param columns columns in discovered ordinal order, never null
     */
    public record PostgresTableView(String name, List<PostgresColumnView> columns) {

        public PostgresTableView {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            columns = List.copyOf(columns);
        }

        static PostgresTableView from(PostgresTable table) {
            return new PostgresTableView(
                    table.name(), table.columns().stream().map(PostgresColumnView::from).toList());
        }
    }

    /**
     * One discovered column: name, 1-based ordinal position, declared type.
     *
     * @param name column name as the source reports it, never blank
     * @param ordinalPosition position within the table, at least 1
     * @param dataType declared data type name, never blank
     */
    public record PostgresColumnView(String name, int ordinalPosition, String dataType) {

        static PostgresColumnView from(PostgresColumn column) {
            return new PostgresColumnView(column.name(), column.ordinalPosition(), column.dataType());
        }
    }
}