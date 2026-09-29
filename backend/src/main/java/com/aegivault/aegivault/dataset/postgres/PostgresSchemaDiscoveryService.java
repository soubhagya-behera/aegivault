package com.aegivault.aegivault.dataset.postgres;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-only PostgreSQL schema discovery: one configured source in, safe
 * metadata out.
 *
 * <p><strong>What is discovered, and only what.</strong> For the source's one
 * configured schema, the base tables and, for each, the column name, ordinal
 * position, and data type name. Nothing else is read: no row values, no
 * samples, no counts, no constraints, no defaults, no comments, no views, no
 * materialized views, and no stored procedures. Discovery therefore cannot copy
 * production data — it never asks the source for a row.
 *
 * <p><strong>The connection boundary.</strong>
 * {@link PostgresDataSource} is asked for one fresh read-only connection, which
 * is closed by the end of the call. Discovery never opens a connection itself,
 * never sees a host, password, or JDBC URL, and never keeps a connection open
 * across a run: one call, one connection, closed in a finally.
 *
 * <p><strong>No SQL is executed, and none can be.</strong> Every fact comes from
 * {@link DatabaseMetaData}, so there is no statement, no query text, and no
 * place for a caller-supplied identifier to be concatenated into anything. The
 * only identifier taken from configuration is the schema name, which is
 * validated against a strict grammar <em>before</em> a connection is opened, and
 * table names read back from the source are escaped into metadata patterns so a
 * name containing {@code %} or {@code _} cannot widen the search.
 *
 * <p><strong>Dependency direction is one-way and narrow</strong>: this service
 * depends on {@link PostgresDataSource} and the JDK, and on nothing else in
 * Aegivault — no dataset persistence, no sanitization run, no gateway, no policy
 * enforcement, no audit ledger, no PII detection, no Redis, and no controller.
 */
@Service
public class PostgresSchemaDiscoveryService {

    /**
     * Discovers one source's configured schema.
     *
     * @param source configured source to inspect, never null
     * @return the discovered schema, never null; an empty table list when the
     *         schema has no base tables
     * @throws IllegalArgumentException when the source's schema name is not a
     *         plain identifier; raised before the source is contacted
     * @throws PostgresSourceConnectionException when the source could not be
     *         reached
     * @throws PostgresSchemaDiscoveryException when the source was reached but
     *         its metadata could not be read
     */
    public PostgresSchema discover(PostgresDataSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        // Validated before any connection exists: an unusable schema name costs
        // nothing, and cannot be used to probe the source.
        String schema = PostgresIdentifier.requireSchemaName(source.schemaName());
        Connection connection = source.openReadOnlyConnection();
        try {
            return readSchema(connection, schema);
        } catch (SQLException ex) {
            throw new PostgresSchemaDiscoveryException(ex);
        } finally {
            close(connection);
        }
    }

    private PostgresSchema readSchema(Connection connection, String schema) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        // The connected database is the catalog; passing it keeps the search
        // inside it rather than letting the driver infer one.
        String catalog = connection.getCatalog();
        String escape = metaData.getSearchStringEscape();
        // The schema is validated as a plain identifier and then escaped as a
        // literal metadata pattern: DatabaseMetaData patterns treat % and _ as
        // wildcards, so a schema legitimately named "reporting_core" must not
        // widen the search to "reportingXcore" or "reporting%".
        String schemaPattern = PostgresIdentifier.literalPattern(schema, escape);
        List<PostgresTable> tables = new ArrayList<>();
        try (ResultSet tableRows = metaData.getTables(catalog, schemaPattern, "%", new String[] {"TABLE"})) {
            while (tableRows.next()) {
                String tableName = tableRows.getString("TABLE_NAME");
                tables.add(new PostgresTable(
                        tableName, readColumns(metaData, catalog, schemaPattern, tableName, escape)));
            }
        }
        return new PostgresSchema(schema, tables);
    }

    private List<PostgresColumn> readColumns(
            DatabaseMetaData metaData, String catalog, String schema, String tableName, String escape)
            throws SQLException {
        // The table name comes from the source, not from a caller, and it is
        // escaped so a name containing a metadata wildcard matches literally.
        String tablePattern = PostgresIdentifier.literalPattern(tableName, escape);
        List<PostgresColumn> columns = new ArrayList<>();
        try (ResultSet columnRows = metaData.getColumns(catalog, schema, tablePattern, "%")) {
            while (columnRows.next()) {
                columns.add(new PostgresColumn(
                        columnRows.getString("COLUMN_NAME"),
                        columnRows.getInt("ORDINAL_POSITION"),
                        columnRows.getString("TYPE_NAME")));
            }
        }
        // Ordinal position is a fact about the source, so it is restored here
        // rather than trusted to whatever order the driver happened to return:
        // a caller mapping a positional format must be able to rely on it.
        columns.sort(java.util.Comparator.comparingInt(PostgresColumn::ordinalPosition));
        return columns;
    }

    /** Closes the caller-owned connection, never masking a discovery failure. */
    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A failure to close does not change what was discovered, and there
            // is nothing safe to report about it.
        }
    }
}
