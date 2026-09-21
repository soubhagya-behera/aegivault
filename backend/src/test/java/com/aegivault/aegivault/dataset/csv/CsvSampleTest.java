package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link CsvSample} (no Spring, no I/O). */
class CsvSampleTest {

    private static final CsvSchema SCHEMA = new CsvSchema(List.of("name", "email"), true);

    @Test
    void exposesSchemaSamplesAndRowAccounting() {
        CsvSample sample = new CsvSample(
                SCHEMA,
                List.of(List.of("Alice", "Bob"), List.of("alice@example.com", "bob@example.com")),
                2,
                5);
        assertThat(sample.schema()).isEqualTo(SCHEMA);
        assertThat(sample.columnSamples()).hasSize(2);
        assertThat(sample.sampledRowCount()).isEqualTo(2);
        assertThat(sample.rowsEncountered()).isEqualTo(5);
        assertThat(sample.sampleTruncated()).isTrue();
    }

    @Test
    void reportsUntruncatedSamples() {
        CsvSample sample = new CsvSample(SCHEMA, List.of(List.of("Alice"), List.of("a@b.co")), 1, 1);
        assertThat(sample.sampleTruncated()).isFalse();
    }

    @Test
    void copiesColumnSamplesDefensively() {
        List<List<String>> columns = new ArrayList<>();
        columns.add(new ArrayList<>(Arrays.asList("Alice")));
        columns.add(new ArrayList<>(Arrays.asList("a@b.co")));
        CsvSample sample = new CsvSample(SCHEMA, columns, 1, 1);
        columns.get(0).add("Bob");
        assertThat(sample.columnSamples().get(0)).containsExactly("Alice");
        assertThatThrownBy(() -> sample.columnSamples().get(0).add("Carol"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> new CsvSample(null, List.of(), 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CsvSample(SCHEMA, null, 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsInconsistentRowAccounting() {
        assertThatThrownBy(() -> new CsvSample(SCHEMA, List.of(List.of("x"), List.of("y")), -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvSample(SCHEMA, List.of(List.of("x"), List.of("y")), 2, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsColumnCountOrSampleSizeMismatch() {
        assertThatThrownBy(() -> new CsvSample(SCHEMA, List.of(List.of("x")), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvSample(SCHEMA, List.of(List.of("x", "y"), List.of("z")), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
