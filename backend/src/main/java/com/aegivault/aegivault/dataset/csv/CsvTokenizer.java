package com.aegivault.aegivault.dataset.csv;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal RFC 4180-style CSV tokenizer.
 *
 * <p>Split into records while invoking the handler once per physical line:
 * fields separated by {@code ,}, fields optionally enclosed in double quotes,
 * {@code ""} as an escaped double quote, and {@code LF}, {@code CRLF}, or a
 * lone {@code CR} as the line terminator. Line terminators inside a quoted
 * field are data. A quoted field must start at the beginning of a field, and
 * only a delimiter, a line terminator, or the end of input may follow its
 * closing quote; anything else is malformed quoting and fails fast instead of
 * being repaired silently.
 *
 * <p>An empty physical line yields a single empty field, and the final line
 * yields a record only when it holds at least one character, so a trailing
 * line terminator does not invent a record. Records arrive in encounter order
 * with the 1-based number of the line where the record starts.
 *
 * <p>Records are handed to the handler one at a time and never accumulated, so
 * memory stays bounded by the limits below rather than by the input length.
 * Instances are stateless and safe to reuse; all parsing state is per call.
 * Raw field values are handed to the caller and are never logged, stored, or
 * included in exception messages, which name the row number, the column
 * index/limit, and nothing else.
 */
final class CsvTokenizer {

    /** Receives one successfully parsed record per physical line. */
    @FunctionalInterface
    interface RecordHandler {

        /**
         * @param rowNumber 1-based number of the line where the record starts
         * @param fields    immutable field values in column order, at least one
         */
        void onRecord(int rowNumber, List<String> fields);
    }

    private final int maxColumns;

    private final int maxFieldLength;

    /**
     * @param maxColumns     maximum fields accepted in one record, at least 1
     * @param maxFieldLength maximum characters accepted in one field, at least 1
     */
    CsvTokenizer(int maxColumns, int maxFieldLength) {
        if (maxColumns < 1) {
            throw new IllegalArgumentException("maxColumns must be at least 1");
        }
        if (maxFieldLength < 1) {
            throw new IllegalArgumentException("maxFieldLength must be at least 1");
        }
        this.maxColumns = maxColumns;
        this.maxFieldLength = maxFieldLength;
    }

    /**
     * Tokenizes {@code text}, reporting one record per physical line.
     *
     * @param text    CSV text to split
     * @param handler callback receiving each record in encounter order
     * @throws CsvParseException when quoting is malformed or a limit is exceeded
     */
    void forEachRecord(String text, RecordHandler handler) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        new RecordParser(text, handler).run();
    }

    /** Per-invocation parsing state; never shared between invocations. */
    private final class RecordParser {

        private final String text;

        private final RecordHandler handler;

        private final List<String> fields = new ArrayList<>();

        private final StringBuilder field = new StringBuilder();

        private int index;

        private int rowNumber = 1;

        private int recordRow = 1;

        private boolean lineHasContent;

        private boolean inQuotes;

        private boolean closedQuotedField;

        RecordParser(String text, RecordHandler handler) {
            this.text = text;
            this.handler = handler;
        }

        void run() {
            int length = text.length();
            while (index < length) {
                char current = text.charAt(index);
                if (inQuotes) {
                    readQuoted(current);
                } else if (current == '\r' || current == '\n') {
                    endRecord();
                } else if (current == '"') {
                    openQuotedField();
                } else if (current == ',') {
                    endField();
                } else if (closedQuotedField) {
                    throw malformed("Malformed CSV quoting");
                } else {
                    append(current);
                }
            }
            if (inQuotes) {
                throw new CsvParseException("Unterminated quoted field starting at row " + recordRow
                        + ", column " + columnIndex() + ".");
            }
            if (lineHasContent) {
                addField();
                emit();
            }
        }

        private void readQuoted(char current) {
            if (current == '"') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    append('"');
                    index++;
                } else {
                    inQuotes = false;
                    closedQuotedField = true;
                    index++;
                }
                return;
            }
            if (current == '\n' || (current == '\r'
                    && !(index + 1 < text.length() && text.charAt(index + 1) == '\n'))) {
                append(current);
                rowNumber++;
                return;
            }
            append(current);
        }

        private void openQuotedField() {
            if (field.length() > 0 || closedQuotedField) {
                throw malformed("Malformed CSV quoting");
            }
            inQuotes = true;
            lineHasContent = true;
            index++;
        }

        private void endField() {
            addField();
            index++;
        }

        private void endRecord() {
            boolean crlf = text.charAt(index) == '\r'
                    && index + 1 < text.length() && text.charAt(index + 1) == '\n';
            addField();
            index += crlf ? 2 : 1;
            emit();
            reset();
        }

        private void addField() {
            if (fields.size() >= maxColumns) {
                throw new CsvParseException("CSV row " + recordRow + " has more than the maximum of "
                        + maxColumns + " columns.");
            }
            fields.add(field.toString());
            field.setLength(0);
            closedQuotedField = false;
        }

        private void append(char current) {
            if (field.length() >= maxFieldLength) {
                throw new CsvParseException("CSV field at row " + recordRow + ", column " + columnIndex()
                        + " exceeds the maximum length of " + maxFieldLength + " characters.");
            }
            field.append(current);
            lineHasContent = true;
            index++;
        }

        private void emit() {
            handler.onRecord(recordRow, List.copyOf(fields));
        }

        private void reset() {
            fields.clear();
            lineHasContent = false;
            inQuotes = false;
            closedQuotedField = false;
            rowNumber++;
            recordRow = rowNumber;
        }

        private int columnIndex() {
            return fields.size() + 1;
        }

        private CsvParseException malformed(String prefix) {
            return new CsvParseException(prefix + " at row " + recordRow + ", column " + columnIndex() + ".");
        }
    }
}
