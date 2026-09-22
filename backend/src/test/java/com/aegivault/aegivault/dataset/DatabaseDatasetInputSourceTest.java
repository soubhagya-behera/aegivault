package com.aegivault.aegivault.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYTEA input storage against real PostgreSQL: owned bytes store and
 * reopen exactly, streams are fresh and independent, ownership matches
 * dataset semantics, oversized input is rejected before anything persists,
 * and dataset metadata stays structurally free of byte content. Small
 * synthetic fixtures only. Each test rolls back.
 */
@SpringBootTest
@Transactional
class DatabaseDatasetInputSourceTest {

    private static final String CSV = "name,email\nbob,bob@example.com\n";

    @Autowired
    private DatabaseDatasetInputSource inputs;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private DatasetInputRepository stored;

    @PersistenceContext
    private EntityManager entities;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    private Dataset dataset(String owner) {
        return datasets.save(new Dataset("customers.csv", owner));
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream input) throws Exception {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ownedInputStoresAndReopensExactly() throws Exception {
        String owner = owner();
        Dataset dataset = dataset(owner);

        inputs.storeInput(owner, dataset.getId(), stream(CSV));

        try (InputStream reopened = inputs.openInput(owner, dataset.getId())) {
            assertThat(reopened).isNotNull();
            assertThat(readAll(reopened)).isEqualTo(CSV);
        }
    }

    @Test
    void reopeningYieldsFreshIndependentStreams() throws Exception {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream(CSV));

        InputStream first = inputs.openInput(owner, dataset.getId());
        InputStream second = inputs.openInput(owner, dataset.getId());

        assertThat(second).isNotSameAs(first);
        assertThat(readAll(first)).isEqualTo(CSV);
        assertThat(readAll(second)).isEqualTo(CSV);
    }

    @Test
    void storingTwiceReplacesTheBytes() throws Exception {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream("a,b\n1,2\n"));

        inputs.storeInput(owner, dataset.getId(), stream(CSV));

        try (InputStream reopened = inputs.openInput(owner, dataset.getId())) {
            assertThat(readAll(reopened)).isEqualTo(CSV);
        }
    }

    @Test
    void foreignAndMissingBehaveIdentically() {
        String ownerA = owner();
        String ownerB = owner();
        Dataset dataset = dataset(ownerA);
        inputs.storeInput(ownerA, dataset.getId(), stream(CSV));

        String foreignStore = null;
        try {
            inputs.storeInput(ownerB, dataset.getId(), stream(CSV));
        } catch (DatasetNotFoundException ex) {
            foreignStore = ex.getMessage();
        }
        String foreignOpen = null;
        try {
            inputs.openInput(ownerB, dataset.getId());
        } catch (DatasetNotFoundException ex) {
            foreignOpen = ex.getMessage();
        }
        String missingOpen = null;
        try {
            inputs.openInput(ownerA, UUID.randomUUID());
        } catch (DatasetNotFoundException ex) {
            missingOpen = ex.getMessage();
        }

        assertThat(foreignStore).isEqualTo("Dataset not found.");
        assertThat(foreignOpen).isEqualTo(missingOpen);
        assertThat(foreignOpen).isEqualTo("Dataset not found.");
    }

    @Test
    void oversizedInputIsRejectedBeforeAnythingPersists() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        byte[] huge = new byte[(int) (10L * 1024L * 1024L) + 1];

        assertThatThrownBy(() -> inputs.storeInput(owner, dataset.getId(), new ByteArrayInputStream(huge)))
                .isInstanceOf(DatasetInputTooLargeException.class)
                .hasMessageContaining("10485760");

        assertThat(stored.findByDatasetIdAndOwnerSubject(dataset.getId(), owner)).isEmpty();
    }

    @Test
    void emptyInputStoresAsIsForEngineValidation() throws Exception {
        String owner = owner();
        Dataset dataset = dataset(owner);

        inputs.storeInput(owner, dataset.getId(), stream(""));

        try (InputStream reopened = inputs.openInput(owner, dataset.getId())) {
            assertThat(readAll(reopened)).isEmpty();
        }
    }

    @Test
    void datasetMetadataCarriesNoByteContent() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream(CSV));
        entities.clear();

        Dataset reloaded = datasets.findByIdAndOwnerSubject(dataset.getId(), owner).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("customers.csv");

        assertThat(entities.getMetamodel().entity(Dataset.class).getAttributes().stream()
                        .map(Attribute::getName)
                        .collect(Collectors.toSet()))
                .doesNotContain("content", "input", "bytes", "payload");
        assertThat(Arrays.stream(DatasetResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .doesNotContain("content", "input", "bytes", "payload");
    }
}
