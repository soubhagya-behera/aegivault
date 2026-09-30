package com.aegivault.aegivault.dataset.postgres;

/**
 * Strict validation for the identifiers this package is trusted with, pattern
 * escaping for identifiers read back from the source, and the PostgreSQL
 * identifier quoting used to build the one row-reading statement.
 *
 * <p><strong>Why validation exists at all.</strong> The metadata half of this
 * milestone never builds SQL, so there is no string to inject into — but JDBC's
 * {@link java.sql.DatabaseMetaData} calls take <em>patterns</em>, where
 * {@code %} and {@code _} are wildcards. A configured schema of
 * {@code pub%} would silently match {@code public} and everything else, and a
 * schema of {@code a_b} would match {@code axb}: metadata patterns are an
 * injection-adjacent surface even when no statement is ever executed. So the
 * configured schema name is validated against a conservative identifier
 * grammar, and any identifier that reaches a metadata call is escaped through
 * the driver's own search-string escape.
 *
 * <p><strong>Row reading is the other half.</strong> Reading rows does build one
 * statement, so the same conservative grammar is applied to the selected table
 * name and to every discovered column name <em>before</em> any SQL is built, and
 * each surviving identifier is then quoted. The grammar admits no double quote,
 * so the quoted form cannot be terminated early and no escaping question arises
 * at all. The consequence is stated rather than hidden: a source whose tables or
 * columns are not plain unquoted identifiers cannot be streamed yet, and is
 * rejected instead of being mis-quoted.
 *
 * <p>Every operation is static, side-effect free, and takes no connection: there
 * is no way to assemble a query from arbitrary parts, and no operation here can
 * execute anything.
 */
final class PostgresIdentifier {

    /** PostgreSQL truncates identifiers beyond this many bytes. */
    static final int MAX_LENGTH = 63;

    private PostgresIdentifier() {}

    /**
     * Validates one configured schema name.
     *
     * <p>Accepted: an unquoted PostgreSQL identifier of at most 63 characters,
     * starting with a letter or underscore and continuing with letters, digits,
     * underscores, or dollars. Everything else is rejected — including quotes,
     * semicolons, whitespace, dashes, dots, commas, and the metadata wildcards
     * {@code %} and {@code _}. Rejection happens before any connection is
     * opened, so an unusable name costs nothing and cannot be probed for.
     *
     * <p>This is stricter than PostgreSQL, which allows quoted identifiers
     * containing almost anything. That is deliberate: a source schema is
     * configuration, and a conservative grammar removes an entire class of
     * ambiguity rather than trying to quote and escape its way out of it.
     *
     * @param schemaName configured schema name, never null or blank
     * @return the name unchanged when it is acceptable
     * @throws IllegalArgumentException when it is not a plain identifier
     */
    static String requireSchemaName(String schemaName) {
        return requirePlainIdentifier(schemaName, "schemaName");
    }

    /**
     * Validates one table name chosen for row reading.
     *
     * <p>Same grammar, same reason, and — the important difference — the same
     * <em>timing</em> as {@link #requireSchemaName(String)}: a table name is
     * rejected before a connection is opened and before any statement is built,
     * so a hostile name costs nothing and cannot be concatenated into anything.
     *
     * <p>A table name read from the catalog is normally already a plain
     * identifier, because PostgreSQL folds unquoted names to lower case. A
     * table that was created with a quoted, mixed-case, or otherwise exotic
     * name is therefore <em>refused</em> rather than mis-quoted: this milestone
     * prefers a safe refusal to an ambiguous one.
     *
     * @param tableName selected table name, never null or blank
     * @return the name unchanged when it is acceptable
     * @throws IllegalArgumentException when it is not a plain identifier
     */
    static String requireTableName(String tableName) {
        return requirePlainIdentifier(tableName, "tableName");
    }

    /**
     * Validates one discovered column name before it is quoted into the
     * statement.
     *
     * <p>Column names come from the source rather than from a caller, so this
     * exists to bound what the source can put in the statement, not to defend
     * against a caller. A table with a column that is not a plain identifier is
     * refused whole, because a partially selectable table would be a silently
     * incomplete read.
     *
     * @param columnName discovered column name, never null or blank
     * @return the name unchanged when it is acceptable
     * @throws IllegalArgumentException when it is not a plain identifier
     */
    static String requireColumnName(String columnName) {
        return requirePlainIdentifier(columnName, "columnName");
    }

    /**
     * Quotes one already-validated identifier for use in the row-reading
     * statement.
     *
     * <p>This is the whole of the SQL-safety story for identifiers, and it is
     * short on purpose: {@link #requirePlainIdentifier(String, String)} admits
     * only letters, digits, underscores, and dollars, so an identifier
     * containing no double quote cannot terminate the quoted form early. The
     * double-quote check below is therefore redundant by construction and is
     * kept as a standing assertion that the two halves cannot drift apart.
     *
     * <p>There is no second, looser entry point: a caller cannot obtain a
     * quoted identifier without having validated it.
     *
     * @param validated identifier already accepted by this class
     * @return the double-quoted identifier
     * @throws IllegalArgumentException when the identifier was not validated
     */
    static String quotedIdentifier(String validated) {
        String identifier = requirePlainIdentifier(validated, "identifier");
        if (identifier.indexOf('"') >= 0) {
            throw new IllegalArgumentException("identifier must be a plain identifier");
        }
        return '"' + identifier + '"';
    }

    /**
     * The one conservative identifier grammar, applied to every identifier that
     * is ever used in a statement.
     *
     * <p>Accepted: an unquoted PostgreSQL identifier of at most 63 characters,
     * starting with a letter or underscore and continuing with letters, digits,
     * underscores, or dollars. Everything else is rejected — including quotes,
     * semicolons, whitespace, dashes, dots, commas, and the metadata wildcards
     * {@code %} and {@code _}. Rejection happens before any connection is
     * opened, so an unusable name costs nothing and cannot be probed for.
     *
     * <p>This is stricter than PostgreSQL, which allows quoted identifiers
     * containing almost anything. That is deliberate: it removes an entire
     * class of ambiguity rather than trying to quote and escape its way out of
     * it.
     *
     * @param value identifier to validate, never null or blank
     * @param field name used in the message; never the value itself
     * @return the name unchanged when it is acceptable
     * @throws IllegalArgumentException when it is not a plain identifier
     */
    private static String requirePlainIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String name = value.trim();
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_LENGTH + " characters");
        }
        char first = name.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            throw new IllegalArgumentException(field + " must start with a letter or underscore");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean allowed = Character.isLetterOrDigit(character) || character == '_' || character == '$';
            if (!allowed) {
                throw new IllegalArgumentException(field + " must be a plain identifier");
            }
        }
        return name;
    }

    /**
     * Escapes one identifier read from the source so it is matched literally as
     * a metadata pattern, never as a wildcard.
     *
     * <p>Used for table names returned by {@code getTables} before they are
     * passed to {@code getColumns}: a table genuinely named {@code a_b} must
     * match itself and not {@code axb}.
     *
     * @param value identifier from metadata, never null
     * @param searchStringEscape the driver's escape character, may be null
     * @return the literal pattern
     */
    static String literalPattern(String value, String searchStringEscape) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        // PostgreSQL's escape character is a backslash; the fallback only
        // matters for a driver that declines to report one.
        String escape = searchStringEscape == null || searchStringEscape.isEmpty() ? "\\" : searchStringEscape;
        StringBuilder pattern = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%' || character == '_' || escape.indexOf(character) >= 0) {
                pattern.append(escape);
            }
            pattern.append(character);
        }
        return pattern.toString();
    }
}
