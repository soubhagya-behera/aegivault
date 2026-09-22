package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link CsvSanitizationWriter} (no Spring, no I/O files). */
class CsvSanitizationWriterTest {

    private static String write(List<String> fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new CsvSanitizationWriter(output).writeRecord(fields);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void writesPlainFieldsWithLfTerminator() {
        assertThat(write(List.of("a", "b", ""))).isEqualTo("a,b,\n");
    }

    @Test
    void quotesCommasQuotesAndLineBreaks() {
        assertThat(write(List.of("has, comma"))).isEqualTo("\"has, comma\"\n");
        assertThat(write(List.of("has \"quote\""))).isEqualTo("\"has \"\"quote\"\"\"\n");
        assertThat(write(List.of("line one\nline two"))).isEqualTo("\"line one\nline two\"\n");
        assertThat(write(List.of("line one\rline two"))).isEqualTo("\"line one\rline two\"\n");
        assertThat(write(List.of("line one\r\nline two"))).isEqualTo("\"line one\r\nline two\"\n");
    }

    @Test
    void preservesColumnCountAndUtf8() {
        assertThat(write(List.of("Jos\u00e9", "Caf\u00e9", ""))).isEqualTo("Jos\u00e9,Caf\u00e9,\n");
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new CsvSanitizationWriter(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> write(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void writeFailureSurfacesAsDomainError() {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("broken");
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("broken");
            }
        };
        assertThatThrownBy(() -> new CsvSanitizationWriter(failing).writeRecord(List.of("a")))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("Unable to write");
        assertThatThrownBy(() -> new CsvSanitizationWriter(failing).flush())
                .isInstanceOf(CsvParseException.class).hasMessageContaining("Unable to write");
    }
}
