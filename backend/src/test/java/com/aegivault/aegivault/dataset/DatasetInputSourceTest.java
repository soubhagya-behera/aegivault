package com.aegivault.aegivault.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/**
 * Contract tests for {@link DatasetInputSource} against real PostgreSQL.
 * No production storage exists, so the tests use an explicit in-test stub
 * that honors the contract (owner-scoped lookup, fresh caller-owned stream
 * per call, identical missing/foreign behavior) without persisting or
 * pretending to persist any bytes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DatasetInputSourceTest {

    private static final String BYTES = "a,b\n1,2\n";

    /**
     * Test-only illustration of the contract: resolves ownership through
     * the real repository, then serves fixed synthetic bytes. Not storage.
     */
    private static final class StubDatasetInputSource implements DatasetInputSource {

        private final DatasetRepository datasets;

        private StubDatasetInputSource(DatasetRepository datasets) {
            this.datasets = datasets;
        }

        @Override
        public InputStream openInput(String ownerSubject, UUID datasetId) {
            if (ownerSubject == null || ownerSubject.isBlank()) {
                throw new IllegalArgumentException("ownerSubject must not be blank");
            }
            if (datasetId == null) {
                throw new NullPointerException("datasetId must not be null");
            }
            datasets.findByIdAndOwnerSubject(datasetId, ownerSubject.trim())
                    .orElseThrow(DatasetNotFoundException::new);
            return new ByteArrayInputStream(BYTES.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Autowired
    private DatasetRepository datasets;

    private DatasetInputSource source() {
        return new StubDatasetInputSource(datasets);
    }

    private Dataset dataset(String owner) {
        return datasets.saveAndFlush(new Dataset("customers.csv", owner));
    }

    private static String readAll(InputStream input) throws IOException {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ownedDatasetYieldsUsableInput() throws Exception {
        Dataset dataset = dataset("owner-1");

        try (InputStream input = source().openInput("owner-1", dataset.getId())) {
            assertThat(input).isNotNull();
            assertThat(readAll(input)).isEqualTo(BYTES);
        }
    }

    @Test
    void foreignAndMissingDatasetsBehaveIdentically() {
        Dataset dataset = dataset("owner-1");

        String foreignMessage = null;
        try {
            source().openInput("owner-2", dataset.getId());
        } catch (DatasetNotFoundException ex) {
            foreignMessage = ex.getMessage();
        }
        String missingMessage = null;
        try {
            source().openInput("owner-1", UUID.randomUUID());
        } catch (DatasetNotFoundException ex) {
            missingMessage = ex.getMessage();
        }

        assertThat(foreignMessage).isNotNull();
        assertThat(foreignMessage).isEqualTo(missingMessage);
        assertThat(foreignMessage).isEqualTo("Dataset not found.");
    }

    @Test
    void everyCallReturnsAFreshStream() throws Exception {
        Dataset dataset = dataset("owner-1");
        DatasetInputSource source = source();

        try (InputStream first = source.openInput("owner-1", dataset.getId());
                InputStream second = source.openInput("owner-1", dataset.getId())) {
            assertThat(second).isNotSameAs(first);
            assertThat(readAll(first)).isEqualTo(BYTES);
            assertThat(readAll(second)).isEqualTo(BYTES);
        }
    }

    @Test
    void invalidInputIsRejected() {
        Dataset dataset = dataset("owner-1");

        assertThatThrownBy(() -> source().openInput("  ", dataset.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source().openInput("owner-1", null))
                .isInstanceOf(NullPointerException.class);
    }
}
