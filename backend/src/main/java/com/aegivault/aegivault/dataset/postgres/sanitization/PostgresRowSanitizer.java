package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.dataset.postgres.PostgresTableRow;

/**
 * Turns one streamed {@link PostgresTableRow} into one sanitized CSV record.
 *
 * <p><strong>This is the whole adapter, and it is deliberately tiny.</strong> The
 * transformation engine is column-oriented and the source is row-oriented, so
 * something must bridge them. That something is exactly these three steps:
 * render each value as text, hand it to the existing engine, write the record.
 * There is no PostgreSQL-specific sanitizer, no transformation of its own, and no
 * second hierarchy — masking, hashing, and synthesis remain entirely inside
 * {@code DataSanitizationService} and its registered strategies.
 *
 * <p><strong>One row in, one record out.</strong> Nothing accumulates: a row is
 * converted and released before the next arrives, so peak memory is a function of
 * the widest single row and the row-stream fetch size, never of the table's size.
 *
 * <p><strong>Nulls are explicit and deterministic.</strong> SQL NULL becomes an
 * empty CSV field. That is stated rather than incidental, because CSV has no null
 * of its own and an empty field is the conventional, lossless-in-practice
 * representation: the source's "absent" becomes the output's "empty", and a
 * genuinely empty source string is indistinguishable from it in CSV either way. The
 * engine is never asked to transform a null, so no detector sees one.
 *
 * <p><strong>No value escapes.</strong> A rendered value is passed to the engine
 * and to the CSV writer and nowhere else. This class logs nothing, throws nothing
 * containing a value, and retains nothing; {@link PostgresTableRow#toString()}
 * withholds values too, so an accidental concatenation cannot leak a cell either.
 *
 * <p><strong>Detection order is the existing registry's.</strong> When one value
 * matches several detectors the same rule the CSV path applies is used: the single
 * detection whose type name sorts first wins. This class never re-implements or
 * re-orders that choice.
 */
final class PostgresRowSanitizer {

    private PostgresRowSanitizer() {}

    /**
     * Converts one value to the text form the engine and the CSV writer expect.
     *
     * <p>The strategy matches the existing profiling conversion exactly, so a
     * value cannot be treated one way when profiled and another when sanitized:
     * SQL NULL stays null, a {@code String} is used verbatim, binary is not
     * decoded, and everything else uses its own {@code toString()}.
     *
     * @param value JDBC value from a streamed row, may be null
     * @return sanitization text, or {@code null} for SQL NULL and for binary
     */
    static String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof byte[]) {
            // Binary is not text; decoding it would fabricate content the source
            // never held, so it is treated as absent rather than guessed at.
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * The CSV rendering of one source value: an empty field for SQL NULL, and
     * the value's own text otherwise.
     *
     * @param value JDBC value from a streamed row, may be null
     * @return never null, so the CSV writer's no-null-fields rule holds
     */
    static String toCsvField(Object value) {
        String text = toText(value);
        return text == null ? "" : text;
    }
}