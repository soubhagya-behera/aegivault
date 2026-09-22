package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
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
        assertThat(view.ownerSubject()).isEqualTo(owner);
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
}
