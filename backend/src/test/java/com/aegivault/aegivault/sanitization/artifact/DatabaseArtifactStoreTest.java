package com.aegivault.aegivault.sanitization.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.run.RunResult;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRun;
import com.aegivault.aegivault.sanitization.run.SanitizationRunNotFoundException;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
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
 * BYTEA artifact storage against real PostgreSQL: owned output stores and
 * reopens exactly, streams are fresh and independent, ownership matches run
 * semantics, oversized output is rejected before anything persists, and run
 * metadata stays structurally free of payload bytes. Small synthetic
 * fixtures only. Each test rolls back.
 */
@SpringBootTest
@Transactional
class DatabaseArtifactStoreTest {

    private static final String OUTPUT = "name,email\nbob,user-abc123def456@example.invalid\n";

    @Autowired
    private DatabaseArtifactStore artifacts;

    @Autowired
    private SanitizationArtifactRepository stored;

    @Autowired
    private SanitizationRunService runs;

    @Autowired
    private DatasetRepository datasets;

    @PersistenceContext
    private EntityManager entities;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    private UUID completedRun(String owner) {
        Dataset dataset = datasets.save(new Dataset("customers.csv", owner));
        SanitizationRunView created = runs.createRun(
                owner, dataset.getId(), DefaultTransformationPolicy.plan(), "default", "v1");
        runs.startRun(owner, created.id());
        return runs.completeRun(owner, created.id(), new RunResult(1L, 1L, 0L, 2)).id();
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
    void ownedArtifactStoresAndReopensExactly() throws Exception {
        String owner = owner();
        UUID runId = completedRun(owner);

        artifacts.storeArtifact(owner, runId, stream(OUTPUT));

        try (InputStream reopened = artifacts.openArtifact(owner, runId)) {
            assertThat(reopened).isNotNull();
            assertThat(readAll(reopened)).isEqualTo(OUTPUT);
        }
    }

    @Test
    void reopeningYieldsFreshIndependentStreams() throws Exception {
        String owner = owner();
        UUID runId = completedRun(owner);
        artifacts.storeArtifact(owner, runId, stream(OUTPUT));

        InputStream first = artifacts.openArtifact(owner, runId);
        InputStream second = artifacts.openArtifact(owner, runId);

        assertThat(second).isNotSameAs(first);
        assertThat(readAll(first)).isEqualTo(OUTPUT);
        assertThat(readAll(second)).isEqualTo(OUTPUT);
    }

    @Test
    void foreignMissingAndNoArtifactBehaveIdentically() {
        String ownerA = owner();
        String ownerB = owner();
        UUID runId = completedRun(ownerA);
        artifacts.storeArtifact(ownerA, runId, stream(OUTPUT));
        UUID bareRun = completedRun(ownerA);

        String foreignMessage = null;
        try {
            artifacts.openArtifact(ownerB, runId);
        } catch (SanitizationRunNotFoundException ex) {
            foreignMessage = ex.getMessage();
        }
        String missingMessage = null;
        try {
            artifacts.openArtifact(ownerA, UUID.randomUUID());
        } catch (SanitizationRunNotFoundException ex) {
            missingMessage = ex.getMessage();
        }
        String noArtifactMessage = null;
        try {
            artifacts.openArtifact(ownerA, bareRun);
        } catch (SanitizationRunNotFoundException ex) {
            noArtifactMessage = ex.getMessage();
        }

        assertThat(foreignMessage).isNotNull();
        assertThat(foreignMessage).isEqualTo(missingMessage);
        assertThat(foreignMessage).isEqualTo(noArtifactMessage);
        assertThat(foreignMessage).isEqualTo("Sanitization run not found.");
    }

    @Test
    void storingForForeignRunIsRejected() {
        String ownerA = owner();
        String ownerB = owner();
        UUID runId = completedRun(ownerA);

        assertThatThrownBy(() -> artifacts.storeArtifact(ownerB, runId, stream(OUTPUT)))
                .isInstanceOf(SanitizationRunNotFoundException.class)
                .hasMessage("Sanitization run not found.");
    }

    @Test
    void oversizedOutputIsRejectedBeforeAnythingPersists() {
        String owner = owner();
        UUID runId = completedRun(owner);
        byte[] huge = new byte[(int) DatabaseArtifactStore.MAX_ARTIFACT_BYTES + 1];

        assertThatThrownBy(() -> artifacts.storeArtifact(owner, runId, new ByteArrayInputStream(huge)))
                .isInstanceOf(ArtifactTooLargeException.class)
                .hasMessageContaining("41943040");

        assertThat(stored.findByRunIdAndOwnerSubject(runId, owner)).isEmpty();
    }

    @Test
    void runMetadataCarriesNoPayloadBytes() {
        String owner = owner();
        UUID runId = completedRun(owner);
        artifacts.storeArtifact(owner, runId, stream(OUTPUT));
        entities.clear();

        SanitizationRunView reloaded = runs.get(owner, runId);
        assertThat(reloaded.status()).isEqualTo(RunStatus.COMPLETED);

        assertThat(entities.getMetamodel().entity(SanitizationRun.class).getAttributes().stream()
                        .map(Attribute::getName)
                        .collect(Collectors.toSet()))
                .doesNotContain("content", "artifact", "bytes", "payload", "output");
        assertThat(Arrays.stream(SanitizationRunView.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .doesNotContain("content", "artifact", "bytes", "payload", "output");
        assertThat(reloaded.toString()).doesNotContain("user-abc123def456");
    }
}
