package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.DatabaseDatasetInputSource;
import com.aegivault.aegivault.sanitization.artifact.DatabaseArtifactStore;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Success-path execution against real PostgreSQL and the real CSV engine:
 * one call creates a run, streams sanitized CSV to the caller's output,
 * persists the structural counts, and leaves the run COMPLETED with the
 * executed plan frozen in its snapshot. Each test rolls back.
 */
@SpringBootTest
@Transactional
class SanitizationRunExecutorTest {

    private static final String CSV = "name,email\nbob,bob@example.com\ncarol,carol@example.com\n\n";

    @Autowired
    private SanitizationRunExecutor executor;

    @Autowired
    private SanitizationRunService runs;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private DatabaseDatasetInputSource inputs;

    @Autowired
    private DatabaseArtifactStore artifacts;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    private Dataset dataset(String owner) {
        return datasets.save(new Dataset("customers.csv", owner));
    }

    private TransformationPlan plan() {
        return DefaultTransformationPolicy.plan();
    }

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private SanitizationRunView execute(String owner, Dataset dataset, ByteArrayOutputStream output) {
        return executor.executeCsv(owner, dataset.getId(), plan(), "default", "v1", stream(CSV), output);
    }

    @Test
    void successfulExecutionCreatesCompletedRunWithMappedCounts() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        SanitizationRunView view = execute(owner, dataset, output);

        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(view.datasetId()).isEqualTo(dataset.getId());
        assertThat(view.inputRowCount()).isEqualTo(2L);
        assertThat(view.outputRowCount()).isEqualTo(2L);
        assertThat(view.blankRowsSkipped()).isEqualTo(1L);
        assertThat(view.columnCount()).isEqualTo(2);
        assertThat(view.startedAt()).isNotNull();
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.errorCode()).isNull();

        SanitizationRunView reloaded = runs.get(owner, view.id());
        assertThat(reloaded.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reloaded.inputRowCount()).isEqualTo(2L);
        assertThat(reloaded.outputRowCount()).isEqualTo(2L);
        assertThat(reloaded.blankRowsSkipped()).isEqualTo(1L);
        assertThat(reloaded.columnCount()).isEqualTo(2);
    }

    @Test
    void versionProgressesAcrossTheLifecycle() {
        String owner = owner();
        SanitizationRunView view = execute(owner, dataset(owner), new ByteArrayOutputStream());

        assertThat(view.version()).isEqualTo(2L);
    }

    @Test
    void engineIsActuallyInvokedAndOutputIsSanitized() {
        String owner = owner();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        execute(owner, dataset(owner), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("name,email\n");
        assertThat(text).doesNotContain("bob@example.com");
        assertThat(text).doesNotContain("carol@example.com");
        assertThat(text).contains("example.invalid");
    }

    @Test
    void persistedSnapshotMatchesTheExecutedPlan() {
        String owner = owner();
        SanitizationRunView view = execute(owner, dataset(owner), new ByteArrayOutputStream());

        assertThat(view.policyName()).isEqualTo("default");
        assertThat(view.policyVersion()).isEqualTo("v1");
        assertThat(view.policySnapshot())
                .isEqualTo(PolicySnapshot.fromPlan("default", "v1", plan()).toJson());
    }

    @Test
    void foreignDatasetCannotBeExecuted() {
        String ownerA = owner();
        String ownerB = owner();
        Dataset dataset = dataset(ownerA);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> executor.executeCsv(
                        ownerB, dataset.getId(), plan(), "default", "v1", stream(CSV), output))
                .isInstanceOf(ReferencedDatasetNotFoundException.class)
                .hasMessage("Dataset not found.");

        assertThat(runs.listByDataset(ownerB, dataset.getId())).isEmpty();
        assertThat(runs.listByDataset(ownerA, dataset.getId())).isEmpty();
        assertThat(output.size()).isEqualTo(0);
    }

    @Test
    void missingDatasetCannotBeExecuted() {
        String owner = owner();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> executor.executeCsv(
                        owner, UUID.randomUUID(), plan(), "default", "v1", stream(CSV), output))
                .isInstanceOf(ReferencedDatasetNotFoundException.class);

        assertThat(output.size()).isEqualTo(0);
    }

    @Test
    void invalidExecutionInputIsRejected() {
        String owner = owner();
        Dataset dataset = dataset(owner);

        assertThatThrownBy(() -> executor.executeCsv(
                        "  ", dataset.getId(), plan(), "default", "v1", stream(CSV),
                        new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.executeCsv(
                        owner, dataset.getId(), plan(), "default", "v1", null,
                        new ByteArrayOutputStream()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executor.executeCsv(
                        owner, dataset.getId(), plan(), "default", "v1", stream(CSV), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(runs.listByDataset(owner, dataset.getId())).isEmpty();
    }

    @Test
    void callerOwnedStreamsAreNotClosed() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        AtomicBoolean inputClosed = new AtomicBoolean(false);
        AtomicBoolean outputClosed = new AtomicBoolean(false);
        InputStream input = new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                inputClosed.set(true);
                super.close();
            }
        };
        OutputStream output = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                outputClosed.set(true);
                super.close();
            }
        };

        SanitizationRunView view =
                executor.executeCsv(owner, dataset.getId(), plan(), "default", "v1", input, output);

        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(inputClosed.get()).isFalse();
        assertThat(outputClosed.get()).isFalse();
    }

    @Test
    void raggedCsvTransitionsRunToFailedWithSafeMetadata() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        SanitizationRunView view = executor.executeCsv(
                owner,
                dataset.getId(),
                plan(),
                "default",
                "v1",
                stream("name,email\nbob,bob@example.com,EXTRA\n"),
                output);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.startedAt()).isNotNull();
        assertThat(view.errorCode()).isEqualTo("CSV_PARSE_ERROR");
        assertThat(view.errorStage()).isEqualTo("TOKENIZE");
        assertThat(view.errorMessage()).isEqualTo("CSV row 2 has 3 columns but the header has 2.");
        assertThat(view.inputRowCount()).isNull();
        assertThat(view.outputRowCount()).isNull();
        assertThat(view.version()).isEqualTo(2L);

        SanitizationRunView reloaded = runs.get(owner, view.id());
        assertThat(reloaded.status()).isEqualTo(RunStatus.FAILED);
        assertThat(reloaded.completedAt()).isNotNull();
        assertThat(reloaded.errorMessage()).isEqualTo("CSV row 2 has 3 columns but the header has 2.");
    }

    @Test
    void failedRunPersistsNoRawCsvOrPii() {
        String owner = owner();
        Dataset dataset = dataset(owner);

        SanitizationRunView view = executor.executeCsv(
                owner,
                dataset.getId(),
                plan(),
                "default",
                "v1",
                stream("name,email\nbob,bob@example.com,EXTRA\n"),
                new ByteArrayOutputStream());

        assertThat(view.errorCode()).doesNotContain("bob@example.com");
        assertThat(view.errorStage()).doesNotContain("bob@example.com");
        assertThat(view.errorMessage()).doesNotContain("bob@example.com", "EXTRA", "bob");
        assertThat(view.errorMessage()).doesNotContain("Exception", "at com.aegivault");
        assertThat(view.policySnapshot()).doesNotContain("bob@example.com");
    }

    @Test
    void policyGapTransitionsRunToFailed() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        TransformationPlan partial = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));

        SanitizationRunView view = executor.executeCsv(
                owner,
                dataset.getId(),
                partial,
                "custom",
                "v1",
                stream("phone\n9876543210\n"),
                new ByteArrayOutputStream());

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.errorCode()).isEqualTo("POLICY_GAP");
        assertThat(view.errorStage()).isEqualTo("TRANSFORM");
        assertThat(view.errorMessage())
                .isEqualTo("No transformation strategy is configured for PII type PHONE.");
        assertThat(view.policySnapshot())
                .isEqualTo(PolicySnapshot.fromPlan("custom", "v1", partial).toJson());
    }

    @Test
    void streamsStayOpenWhenExecutionFails() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        AtomicBoolean inputClosed = new AtomicBoolean(false);
        AtomicBoolean outputClosed = new AtomicBoolean(false);
        InputStream input = new ByteArrayInputStream(
                "a,b\n1,2,3\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                inputClosed.set(true);
                super.close();
            }
        };
        OutputStream output = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                outputClosed.set(true);
                super.close();
            }
        };

        SanitizationRunView view =
                executor.executeCsv(owner, dataset.getId(), plan(), "default", "v1", input, output);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(inputClosed.get()).isFalse();
        assertThat(outputClosed.get()).isFalse();
    }

    @Test
    void storedInputExecutesPersistedBytes() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream(CSV));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        SanitizationRunView view =
                executor.executeStoredCsv(owner, dataset.getId(), plan(), "default", "v1", output);

        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(view.datasetId()).isEqualTo(dataset.getId());
        assertThat(view.inputRowCount()).isEqualTo(2L);
        assertThat(view.outputRowCount()).isEqualTo(2L);
        assertThat(view.blankRowsSkipped()).isEqualTo(1L);
        assertThat(view.columnCount()).isEqualTo(2);
        assertThat(view.version()).isEqualTo(2L);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("name,email\n");
        assertThat(text).doesNotContain("bob@example.com");
        assertThat(text).contains("example.invalid");
    }

    @Test
    void foreignStoredInputCannotBeExecuted() {
        String ownerA = owner();
        String ownerB = owner();
        Dataset dataset = dataset(ownerA);
        inputs.storeInput(ownerA, dataset.getId(), stream(CSV));

        assertThatThrownBy(() -> executor.executeStoredCsv(
                        ownerB, dataset.getId(), plan(), "default", "v1", new ByteArrayOutputStream()))
                .isInstanceOf(ReferencedDatasetNotFoundException.class)
                .hasMessage("Dataset not found.");

        assertThat(runs.listByDataset(ownerB, dataset.getId())).isEmpty();
    }

    @Test
    void missingDatasetAndMissingInputBehaveIdentically() {
        String owner = owner();
        Dataset withoutInput = dataset(owner);

        String missingMessage = null;
        try {
            executor.executeStoredCsv(
                    owner, UUID.randomUUID(), plan(), "default", "v1", new ByteArrayOutputStream());
        } catch (ReferencedDatasetNotFoundException ex) {
            missingMessage = ex.getMessage();
        }
        String noInputMessage = null;
        try {
            executor.executeStoredCsv(
                    owner, withoutInput.getId(), plan(), "default", "v1", new ByteArrayOutputStream());
        } catch (ReferencedDatasetNotFoundException ex) {
            noInputMessage = ex.getMessage();
        }

        assertThat(missingMessage).isNotNull();
        assertThat(missingMessage).isEqualTo(noInputMessage);
        assertThat(runs.listByDataset(owner, withoutInput.getId())).isEmpty();
    }

    @Test
    void storedRaggedCsvProducesFailedRun() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream("a,b\n1,2,3\n"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        SanitizationRunView view =
                executor.executeStoredCsv(owner, dataset.getId(), plan(), "default", "v1", output);

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.errorCode()).isEqualTo("CSV_PARSE_ERROR");
        assertThat(view.errorMessage()).isEqualTo("CSV row 2 has 3 columns but the header has 2.");
        assertThat(view.inputRowCount()).isNull();

        SanitizationRunView reloaded = runs.get(owner, view.id());
        assertThat(reloaded.status()).isEqualTo(RunStatus.FAILED);
        assertThat(reloaded.errorMessage()).doesNotContain("1,2,3");
    }

    @Test
    void storedExecutionLeavesOutputOpen() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream(CSV));
        AtomicBoolean outputClosed = new AtomicBoolean(false);
        OutputStream output = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                outputClosed.set(true);
                super.close();
            }
        };

        SanitizationRunView view =
                executor.executeStoredCsv(owner, dataset.getId(), plan(), "default", "v1", output);

        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outputClosed.get()).isFalse();
    }

    @Test
    void storedExecutionPersistsExactlyOneMatchingArtifact() throws Exception {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream(CSV));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        SanitizationRunView view =
                executor.executeStoredCsv(owner, dataset.getId(), plan(), "default", "v1", output);

        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        String sanitized = output.toString(StandardCharsets.UTF_8);
        try (InputStream reopened = artifacts.openArtifact(owner, view.id())) {
            assertThat(new String(reopened.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(sanitized);
        }
        assertThat(sanitized).doesNotContain("bob@example.com");
    }

    @Test
    void storedArtifactBelongsToItsRunOnly() throws Exception {
        String owner = owner();
        Dataset firstDataset = dataset(owner);
        Dataset secondDataset = dataset(owner);
        inputs.storeInput(owner, firstDataset.getId(), stream(CSV));
        inputs.storeInput(owner, secondDataset.getId(), stream(CSV));

        SanitizationRunView first =
                executor.executeStoredCsv(owner, firstDataset.getId(), plan(), "default", "v1",
                        new ByteArrayOutputStream());
        SanitizationRunView second =
                executor.executeStoredCsv(owner, secondDataset.getId(), plan(), "default", "v1",
                        new ByteArrayOutputStream());

        try (InputStream firstArtifact = artifacts.openArtifact(owner, first.id());
                InputStream secondArtifact = artifacts.openArtifact(owner, second.id())) {
            assertThat(firstArtifact).isNotNull();
            assertThat(secondArtifact).isNotNull();
        }
        assertThatThrownBy(() -> artifacts.openArtifact(owner, UUID.randomUUID()))
                .isInstanceOf(SanitizationRunNotFoundException.class);
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void failedStoredExecutionPersistsNoArtifact() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        inputs.storeInput(owner, dataset.getId(), stream("a,b\n1,2,3\n"));

        SanitizationRunView view = executor.executeStoredCsv(
                owner, dataset.getId(), plan(), "default", "v1", new ByteArrayOutputStream());

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThatThrownBy(() -> artifacts.openArtifact(owner, view.id()))
                .isInstanceOf(SanitizationRunNotFoundException.class);
    }

    @Test
    void overflowingOutputFailsRunWithoutArtifact() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        int rows = (int) (DatabaseArtifactStore.MAX_ARTIFACT_BYTES / 65) + 100;
        StringBuilder input = new StringBuilder("ip\n");
        for (int index = 0; index < rows; index++) {
            input.append("1.1.1.1\n");
        }
        inputs.storeInput(owner, dataset.getId(), stream(input.toString()));

        SanitizationRunView view = executor.executeStoredCsv(
                owner, dataset.getId(), plan(), "default", "v1", new ByteArrayOutputStream());

        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.errorCode()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(view.errorStage()).isEqualTo("WRITE");
        assertThat(view.errorMessage()).contains("41943040");
        assertThat(view.errorMessage()).doesNotContain("1.1.1.1");
        assertThatThrownBy(() -> artifacts.openArtifact(owner, view.id()))
                .isInstanceOf(SanitizationRunNotFoundException.class);
    }
}
