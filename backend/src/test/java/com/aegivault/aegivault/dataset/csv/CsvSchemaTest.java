package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link CsvSchema} (no Spring, no I/O). */
class CsvSchemaTest {

    @Test
    void exposesColumnNamesAndDerivedCount() {
        CsvSchema schema = new CsvSchema(List.of("name", "email"), true);
        assertThat(schema.columnNames()).containsExactly("name", "email");
        assertThat(schema.columnCount()).isEqualTo(2);
        assertThat(schema.headerPresent()).isTrue();
    }

    @Test
    void copiesColumnNamesDefensively() {
        List<String> names = new ArrayList<>(List.of("name"));
        CsvSchema schema = new CsvSchema(names, true);
        names.add("email");
        assertThat(schema.columnNames()).containsExactly("name");
        assertThatThrownBy(() -> schema.columnNames().add("phone"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingColumnNames() {
        assertThatThrownBy(() -> new CsvSchema(null, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CsvSchema(List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankColumnNames() {
        assertThatThrownBy(() -> new CsvSchema(List.of("name", "  "), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvSchema(java.util.Arrays.asList("name", null), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverContainsCellValues() {
        CsvSchema schema = new CsvSchema(List.of("email"), true);
        assertThat(schema.toString()).doesNotContain("@");
    }
}
