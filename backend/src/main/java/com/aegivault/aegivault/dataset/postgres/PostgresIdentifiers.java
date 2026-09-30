package com.aegivault.aegivault.dataset.postgres;

/**
 * The strict PostgreSQL identifier grammar, exposed to other Aegivault packages
 * that must validate a schema or table name without re-implementing the rules.
 *
 * <p><strong>Reuse, not duplication.</strong> A name this class accepts is
 * exactly a name {@link PostgresSchemaDiscoveryService} discovered and the row
 * source will later read, so no layer can end up holding a name another layer
 * would reject. There is deliberately no second, looser grammar anywhere in the
 * codebase.
 *
 * <p><strong>Validation only.</strong> Nothing here builds a statement, opens a
 * connection, or reaches a database, and the quoting used to build the
 * row-reading statement stays private to {@code PostgresIdentifier}. A caller can
 * check whether a name is a plain identifier; it still cannot make this package
 * execute anything.
 */
public final class PostgresIdentifiers {

    private PostgresIdentifiers() {}

    /**
     * Validates one PostgreSQL schema name.
     *
     * @param schemaName candidate schema name, never null or blank
     * @return the name unchanged when it is acceptable
     * @throws IllegalArgumentException when it is not a plain identifier
     * @see PostgresIdentifier#requireSchemaName(String)
     */
    public static String requireSchemaName(String schemaName) {
        return PostgresIdentifier.requireSchemaName(schemaName);
    }

    /**
     * Validates one PostgreSQL base table name.
     *
     * @param tableName candidate table name, never null or blank
     * @return the name unchanged when it is acceptable
     * @throws IllegalArgumentException when it is not a plain identifier
     * @see PostgresIdentifier#requireTableName(String)
     */
    public static String requireTableName(String tableName) {
        return PostgresIdentifier.requireTableName(tableName);
    }
}
