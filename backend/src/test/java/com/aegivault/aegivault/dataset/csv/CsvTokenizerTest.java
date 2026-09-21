package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link CsvTokenizer} (no Spring, no I/O). */
class CsvTokenizerTest {

    private static final int MAX_COLUMNS = 10;

    private static final int MAX_FIELD_LENGTH = 100;

    private record ParsedRecord(int rowNumber, List<String> fields) {
    }

    private static List<ParsedRecord> tokenize(String text) {
        return tokenize(MAX_COLUMNS, MAX_FIELD_LENGTH, text);
    }

    private static List<ParsedRecord> tokenize(int maxColumns, int maxFieldLength, String text) {
        List<ParsedRecord> records = new ArrayList<>();
        new CsvTokenizer(maxColumns, maxFieldLength)
                .forEachRecord(text, (rowNumber, fields) -> records.add(new ParsedRecord(rowNumber, fields)));
        return records;
    }

    private static List<String> fieldsOfFirstRecord(String text) {
        return tokenize(text).get(0).fields();
    }

    @Test
    void splitsPlainRecord() {
        assertThat(fieldsOfFirstRecord("a,b,c")).containsExactly("a", "b", "c");
    }

    @Test
    void splitsLinesWithLf() {
        List<ParsedRecord> records = tokenize("a,b\nc,d\n");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).rowNumber()).isEqualTo(1);
        assertThat(records.get(1).rowNumber()).isEqualTo(2);
        assertThat(records.get(1).fields()).containsExactly("c", "d");
    }

    @Test
    void splitsLinesWithCrlf() {
        List<ParsedRecord> records = tokenize("a,b\r\nc,d\r\n");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).fields()).containsExactly("a", "b");
        assertThat(records.get(1).fields()).containsExactly("c", "d");
    }

    @Test
    void splitsLinesWithLoneCarriageReturn() {
        List<ParsedRecord> records = tokenize("a,b\rc,d");
        assertThat(records).hasSize(2);
        assertThat(records.get(1).fields()).containsExactly("c", "d");
    }

    @Test
    void keepsDelimiterInsideQuotedField() {
        assertThat(fieldsOfFirstRecord("a,\"b,c\""))
                .containsExactly("a", "b,c");
    }

    @Test
    void keepsLineBreakInsideQuotedField() {
        assertThat(fieldsOfFirstRecord("\"a\nb\",c"))
                .containsExactly("a\nb", "c");
    }

    @Test
    void countsRowNumbersAcrossQuotedLineBreaks() {
        List<ParsedRecord> records = tokenize("\"x\ny\",b\nv,w\n");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).rowNumber()).isEqualTo(1);
        assertThat(records.get(0).fields()).containsExactly("x\ny", "b");
        assertThat(records.get(1).rowNumber()).isEqualTo(3);
    }

    @Test
    void unescapesDoubledQuotes() {
        assertThat(fieldsOfFirstRecord("\"She said \"\"hello\"\"\""))
                .containsExactly("She said \"hello\"");
    }

    @Test
    void keepsEmptyFields() {
        assertThat(fieldsOfFirstRecord("a,,c")).containsExactly("a", "", "c");
        assertThat(fieldsOfFirstRecord("a,")).containsExactly("a", "");
        assertThat(fieldsOfFirstRecord("\"\",b")).containsExactly("", "b");
    }

    @Test
    void emitsSingleEmptyFieldForEmptyLine() {
        List<ParsedRecord> records = tokenize("a,b\n\n\n");
        assertThat(records).hasSize(3);
        assertThat(records.get(1).fields()).containsExactly("");
        assertThat(records.get(2).fields()).containsExactly("");
    }

    @Test
    void emitsNoRecordForEmptyText() {
        assertThat(tokenize("")).isEmpty();
    }

    @Test
    void emitsNoPhantomRecordAfterTrailingTerminator() {
        assertThat(tokenize("a,b\n")).hasSize(1);
        assertThat(tokenize("a,b\r\n")).hasSize(1);
    }

    @Test
    void reportsImmutableFieldLists() {
        new CsvTokenizer(MAX_COLUMNS, MAX_FIELD_LENGTH).forEachRecord("a,b", (rowNumber, fields) ->
                assertThatThrownBy(() -> fields.add("x")).isInstanceOf(UnsupportedOperationException.class));
    }

    @Test
    void tokenizerInstancesAreReusable() {
        CsvTokenizer tokenizer = new CsvTokenizer(MAX_COLUMNS, MAX_FIELD_LENGTH);
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        tokenizer.forEachRecord("a,b\n", (rowNumber, fields) -> first.addAll(fields));
        tokenizer.forEachRecord("c,d\n", (rowNumber, fields) -> second.addAll(fields));
        assertThat(first).containsExactly("a", "b");
        assertThat(second).containsExactly("c", "d");
    }

    @Test
    void rejectsInvalidLimits() {
        assertThatThrownBy(() -> new CsvTokenizer(0, MAX_FIELD_LENGTH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvTokenizer(MAX_COLUMNS, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullArguments() {
        CsvTokenizer tokenizer = new CsvTokenizer(MAX_COLUMNS, MAX_FIELD_LENGTH);
        assertThatThrownBy(() -> tokenizer.forEachRecord(null, (rowNumber, fields) -> { /* unused */ }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> tokenizer.forEachRecord("a,b", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsQuoteInsideUnquotedField() {
        assertThatThrownBy(() -> tokenize("ab\"cd"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Malformed CSV quoting")
                .hasMessageContaining("row 1")
                .hasMessageContaining("column 1");
    }

    @Test
    void rejectsTextAfterClosingQuote() {
        assertThatThrownBy(() -> tokenize("\"a\"b,c"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Malformed CSV quoting");
    }

    @Test
    void rejectsQuoteInsideLaterField() {
        assertThatThrownBy(() -> tokenize("\"a\",b\"c"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Malformed CSV quoting")
                .hasMessageContaining("row 1")
                .hasMessageContaining("column 2");
    }

    @Test
    void rejectsUnterminatedQuotedField() {
        assertThatThrownBy(() -> tokenize("a,\"unterminated\nnext"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Unterminated quoted field starting at row 1, column 2");
    }

    @Test
    void rejectsTooManyColumnsInOneRecord() {
        assertThatThrownBy(() -> tokenize(2, MAX_FIELD_LENGTH, "a,b,c"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("row 1")
                .hasMessageContaining("maximum of 2 columns");
    }

    @Test
    void rejectsOverlongUnquotedField() {
        assertThatThrownBy(() -> tokenize(MAX_COLUMNS, 3, "abc,abcd"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("row 1")
                .hasMessageContaining("column 2")
                .hasMessageContaining("maximum length of 3");
    }

    @Test
    void rejectsOverlongQuotedField() {
        assertThatThrownBy(() -> tokenize(MAX_COLUMNS, 3, "\"abcd\""))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("maximum length of 3");
    }

    @Test
    void parsingErrorsNeverContainFieldValues() {
        String sensitiveValue = "alice@example.com";
        assertThatThrownBy(() -> tokenize(sensitiveValue + "\""))
                .isInstanceOf(CsvParseException.class)
                .hasMessageNotContaining(sensitiveValue);
        assertThatThrownBy(() -> tokenize(MAX_COLUMNS, 5, sensitiveValue))
                .isInstanceOf(CsvParseException.class)
                .hasMessageNotContaining(sensitiveValue);
        assertThatThrownBy(() -> tokenize(MAX_COLUMNS, MAX_FIELD_LENGTH, sensitiveValue + "\n\"unterminated"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageNotContaining(sensitiveValue);
    }
}
