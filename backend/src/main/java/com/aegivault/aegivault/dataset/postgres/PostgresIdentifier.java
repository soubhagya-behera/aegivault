package com.aegivault.aegivault.dataset.postgres;

/**
 * Strict validation for the one identifier this package is trusted with, and
 * pattern escaping for identifiers read back from the source.
 *
 * <p><strong>Why validation exists at all.</strong> This milestone never builds
 * SQL, so there is no string to inject into — but JDBC's
 * {@link java.sql.DatabaseMetaData} calls take <em>patterns</em>, where
 * {@code %} and {@code _} are wildcards. A configured schema of
 * {@code pub%} would silently match {@code public} and everything else, and a
 * schema of {@code a_b} would match {@code axb}: metadata patterns are an
 * injection-adjacent surface even when no statement is ever executed. So the
 * configured schema name is validated against a conservative identifier
 * grammar, and any identifier that reaches a metadata call is escaped through
 * the driver's own search-string escape.
 *
 * <p>Only two operations are exposed, both static and side-effect free: no SQL,
 * no quoting helper for statements, and no way to assemble a query from parts.
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
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("schemaName must not be blank");
        }
        String name = schemaName.trim();
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("schemaName must be at most " + MAX_LENGTH + " characters");
        }
        char first = name.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            throw new IllegalArgumentException("schemaName must start with a letter or underscore");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean allowed = Character.isLetterOrDigit(character) || character == '_' || character == '$';
            if (!allowed) {
                throw new IllegalArgumentException("schemaName must be a plain identifier");
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
