package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link CsvDiscoveryService} (no Spring, no database). */
class CsvDiscoveryServiceTest {

    private static final CsvLimits SMALL_LIMITS = new CsvLimits(4, 2, 12, 4096);

    private static CsvSample discover(String csv) {
        return discover(csv, CsvLimits.defaults());
    }

    private static CsvSample discover(String csv, CsvLimits limits) {
        return new CsvDiscoveryService(limits).discover(stream(csv));
    }

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void discoversHeaderAndSamples() {
        CsvSample sample = discover("""
                name,email,phone
                Alice,alice@example.com,9876543210
                Bob,bob@example.com,9123456789
                """);
        assertThat(sample.schema().columnNames()).containsExactly("name", "email", "phone");
        assertThat(sample.schema().columnCount()).isEqualTo(3);
        assertThat(sample.schema().headerPresent()).isTrue();
        assertThat(sample.columnSamples().get(0)).containsExactly("Alice", "Bob");
        assertThat(sample.columnSamples().get(1)).containsExactly("alice@example.com", "bob@example.com");
        assertThat(sample.columnSamples().get(2)).containsExactly("9876543210", "9123456789");
        assertThat(sample.sampledRowCount()).isEqualTo(2);
        assertThat(sample.rowsEncountered()).isEqualTo(2);
        assertThat(sample.sampleTruncated()).isFalse();
    }

    @Test
    void keepsQuotedDelimiterInsideOneColumn() {
        CsvSample sample = discover("""
                name,address
                Alice,"221 Baker Street, London"
                """);
        assertThat(sample.schema().columnCount()).isEqualTo(2);
        assertThat(sample.columnSamples().get(1)).containsExactly("221 Baker Street, London");
    }

    @Test
    void unescapesQuotedQuotes() {
        CsvSample sample = discover("""
                name,description
                Alice,"She said ""hello"" loudly"
                """);
        assertThat(sample.columnSamples().get(1)).containsExactly("She said \"hello\" loudly");
    }

    @Test
    void keepsEmptyValues() {
        CsvSample sample = discover("name,phone\nAlice,\n");
        assertThat(sample.columnSamples().get(0)).containsExactly("Alice");
        assertThat(sample.columnSamples().get(1)).containsExactly("");
        assertThat(sample.sampledRowCount()).isEqualTo(1);
    }

    @Test
    void acceptsLfCrlfLoneCrAndBom() {
        assertThat(discover("a,b\n1,2\n").schema().columnNames()).containsExactly("a", "b");
        assertThat(discover("a,b\r\n1,2\r\n").schema().columnNames()).containsExactly("a", "b");
        assertThat(discover("a,b\r1,2\r").schema().columnNames()).containsExactly("a", "b");
        assertThat(discover("a,b\r1,2\r").columnSamples().get(0)).containsExactly("1");
        assertThat(discover("\uFEFFa,b\n1,2\n").schema().columnNames()).containsExactly("a", "b");
    }

    @Test
    void readsUtf8Values() {
        CsvSample sample = discover("name,city\nJos\u00e9,Caf\u00e9\n");
        assertThat(sample.columnSamples().get(0)).containsExactly("Jos\u00e9");
        assertThat(sample.columnSamples().get(1)).containsExactly("Caf\u00e9");
    }

    @Test
    void skipsBlankRecords() {
        CsvSample sample = discover("a,b\n\n   \n,,\n1,2\n");
        assertThat(sample.rowsEncountered()).isEqualTo(1);
        assertThat(sample.sampledRowCount()).isEqualTo(1);
        assertThat(sample.columnSamples().get(0)).containsExactly("1");
        assertThat(sample.columnSamples().get(1)).containsExactly("2");
    }

    @Test
    void skipsBlankRecordBetweenDataRows() {
        CsvSample sample = discover("a,b\n1,2\n,\n3,4\n");
        assertThat(sample.rowsEncountered()).isEqualTo(2);
        assertThat(sample.columnSamples().get(0)).containsExactly("1", "3");
    }

    @Test
    void countsRowsAcrossQuotedLineBreaks() {
        CsvSample sample = discover("a,b\n\"x\ny\",1\n2,3\n");
        assertThat(sample.columnSamples().get(0)).containsExactly("x\ny", "2");
        assertThat(sample.rowsEncountered()).isEqualTo(2);
    }

    @Test
    void columnSamplesAreImmutable() {
        CsvSample sample = discover("a,b\n1,2\n");
        assertThatThrownBy(() -> sample.columnSamples().get(0).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sample.schema().columnNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void outputIsDeterministic() {
        String csv = "a,b\n1,2\n3,4\n";
        assertThat(discover(csv)).isEqualTo(discover(csv));
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new CsvDiscoveryService(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CsvDiscoveryService(CsvLimits.defaults()).discover((InputStream) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> discover(""))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV input is empty");
    }

    @Test
    void rejectsBlankHeaderRow() {
        assertThatThrownBy(() -> discover("\nname,email\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("header row 1 is blank");
        assertThatThrownBy(() -> discover("   \nname,email\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("header row 1 is blank");
        assertThatThrownBy(() -> discover(",,\n1,2,3\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("header row 1 is blank");
    }

    @Test
    void rejectsBlankHeaderColumnWithoutEchoingNames() {
        assertThatThrownBy(() -> discover("name,,phone\nAlice,x,9876543210\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("header column 2 is blank")
                .hasMessageNotContaining("name")
                .hasMessageNotContaining("phone");
        assertThatThrownBy(() -> discover("name, ,phone\nAlice,x,9876543210\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("header column 2 is blank")
                .hasMessageNotContaining("name")
                .hasMessageNotContaining("phone");
    }

    @Test
    void rejectsDuplicateHeaderNamesWithoutEchoingNames() {
        assertThatThrownBy(() -> discover("email,email\na@b.co,c@d.co\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("header column 2 duplicates an earlier column name")
                .hasMessageNotContaining("email");
    }

    @Test
    void rejectsDuplicateHeaderNamesIgnoringCaseAndPadding() {
        assertThatThrownBy(() -> discover("Email,email\na@b.co,c@d.co\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("duplicates an earlier column name");
        assertThatThrownBy(() -> discover("email, email \na@b.co,c@d.co\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("duplicates an earlier column name");
    }

    @Test
    void preservesHeaderNamesVerbatim() {
        CsvSample sample = discover(" name ,email\nAlice,a@b.co\n");
        assertThat(sample.schema().columnNames()).containsExactly(" name ", "email");
    }

    @Test
    void allowsDistinctHeaderNamesThatOnlyShareAPrefix() {
        CsvSample sample = discover("email,email_address\na@b.co,b@c.co\n");
        assertThat(sample.schema().columnCount()).isEqualTo(2);
    }

    @Test
    void rejectsRowWithFewerColumnsWithoutEchoingValues() {
        assertThatThrownBy(() -> discover("name,email\nalice@example.com\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 2 has 1 columns but the header has 2")
                .hasMessageNotContaining("alice@example.com");
    }

    @Test
    void rejectsRowWithMoreColumnsWithoutEchoingValues() {
        assertThatThrownBy(() -> discover("name,email\nAlice,alice@example.com,extra\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 2 has 3 columns but the header has 2")
                .hasMessageNotContaining("alice@example.com")
                .hasMessageNotContaining("extra");
    }

    @Test
    void rejectsRowWidthMismatchEvenBeyondTheSample() {
        CsvDiscoveryService service = new CsvDiscoveryService(new CsvLimits(4, 1, 12, 4096));
        assertThatThrownBy(() -> service.discover(stream("a,b\n1,2\n3,4,5\n")))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 3 has 3 columns but the header has 2");
    }

    @Test
    void reportsRowNumbersAfterQuotedLineBreaks() {
        assertThatThrownBy(() -> discover("a,b\n\"x\ny\",1\n2\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 4 has 1 columns but the header has 2");
    }

    @Test
    void propagatesMalformedQuotingAsDomainError() {
        assertThatThrownBy(() -> discover("name\n\"unterminated\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Unterminated quoted field starting at row 2");
    }

    @Test
    void samplesOnlyTheConfiguredLimit() {
        CsvSample sample = discover("a,b\n1,2\n3,4\n5,6\n", new CsvLimits(4, 2, 12, 4096));
        assertThat(sample.sampledRowCount()).isEqualTo(2);
        assertThat(sample.rowsEncountered()).isEqualTo(3);
        assertThat(sample.sampleTruncated()).isTrue();
        assertThat(sample.columnSamples().get(0)).containsExactly("1", "3");
        assertThat(sample.columnSamples().get(1)).containsExactly("2", "4");
    }

    @Test
    void honoursExplicitlyRequestedSampleSize() {
        CsvDiscoveryService service = new CsvDiscoveryService(CsvLimits.defaults());
        CsvSample sample = service.discover(stream("a,b\n1,2\n3,4\n5,6\n"), 1);
        assertThat(sample.sampledRowCount()).isEqualTo(1);
        assertThat(sample.rowsEncountered()).isEqualTo(3);
        assertThat(sample.sampleTruncated()).isTrue();
        assertThat(sample.columnSamples().get(0)).containsExactly("1");
    }

    @Test
    void rejectsRequestedSampleSizeAboveConfiguredCeiling() {
        CsvDiscoveryService service = new CsvDiscoveryService(new CsvLimits(4, 4, 12, 4096));
        assertThatThrownBy(() -> service.discover(stream("a,b\n1,2\n"), 5))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Requested CSV sample size 5 exceeds the configured maximum of 4");
    }

    @Test
    void rejectsRequestedSampleSizeBelowOne() {
        CsvDiscoveryService service = new CsvDiscoveryService(CsvLimits.defaults());
        assertThatThrownBy(() -> service.discover(stream("a,b\n1,2\n"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoreColumnsThanTheLimit() {
        assertThatThrownBy(() -> discover("a,b,c,d,e\n1,2,3,4,5\n", new CsvLimits(4, 2, 12, 4096)))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 1 has more than the maximum of 4 columns");
    }

    @Test
    void rejectsFieldLongerThanTheLimit() {
        assertThatThrownBy(() -> discover("a,b\nab,abcde\n", new CsvLimits(4, 2, 4, 4096)))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV field at row 2, column 2 exceeds the maximum length of 4")
                .hasMessageNotContaining("abcde");
    }

    @Test
    void rejectsInputLargerThanTheLimit() {
        assertThatThrownBy(() -> discover("a,b\n1,2\n3,4\n", new CsvLimits(4, 2, 12, 8)))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("exceeds the maximum supported size of 8 bytes");
    }
}
