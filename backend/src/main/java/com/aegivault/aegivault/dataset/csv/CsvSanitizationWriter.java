package com.aegivault.aegivault.dataset.csv;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Focused CSV record writer used only by the sanitization pipeline.
 *
 * <p>Escapes one record at a time directly to the caller's stream: a field is
 * quoted only when it contains a comma, a double quote, {@code LF}, or
 * {@code CR}; an embedded double quote is escaped as two double quotes; empty
 * values stay empty. Every record ends with a documented {@code LF}
 * terminator, so {@code CRLF} and lone-{@code CR} inputs are normalized on
 * output while the column count is always preserved. Nothing is buffered
 * beyond one record, nothing is logged, and this class never closes or owns
 * the stream.
 */
final class CsvSanitizationWriter {

    private static final byte[] LINE_FEED = {'\n'};

    private final OutputStream output;

    CsvSanitizationWriter(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    /**
     * Writes one record with exactly the supplied column count.
     *
     * @param fields field values in column order, at least one, no null entries
     */
    void writeRecord(List<String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        try {
            for (int index = 0; index < fields.size(); index++) {
                if (index > 0) {
                    output.write(',');
                }
                String field = Objects.requireNonNull(fields.get(index), "field must not be null");
                output.write(escape(field).getBytes(StandardCharsets.UTF_8));
            }
            output.write(LINE_FEED);
        } catch (IOException ex) {
            throw new CsvParseException("Unable to write sanitized CSV output.");
        }
    }

    /**
     * Flushes the underlying stream without closing it.
     */
    void flush() {
        try {
            output.flush();
        } catch (IOException ex) {
            throw new CsvParseException("Unable to write sanitized CSV output.");
        }
    }

    private static String escape(String field) {
        boolean quoted = field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0;
        if (!quoted) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
