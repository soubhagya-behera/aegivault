package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped read-only retrieval of persisted runs against real
 * PostgreSQL. Retrieval goes through the existing
 * {@code findByIdAndOwnerSubject} boundary: the owner reads their own
 * completed and failed runs, foreign and missing ids are indistinguishable,
 * and the view exposes safe metadata only. Each test rolls back.
 */
@SpringBootTest
@Transactional
class SanitizationRunRetrievalTest {

    @Autowired
    private SanitizationRunExecutor executor;

    @Autowired
    private SanitizationRunService runs;

    @Autowired
    private DatasetRepository datasets;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    private SanitizationRunView completedRun(String owner) {
        Dataset dataset = datasets.save(new Dataset("customers.csv", owner));
        return executor.executeCsv(
                owner,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream("name,email\nbob,bob@example.com\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
    }

    private SanitizationRunView failedRun(String owner) {
        Dataset dataset = datasets.save(new Dataset("ragged.csv", owner));
        return executor.executeCsv(
                owner,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream("a,b\n1,2,3\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
    }

    @Test
    void ownerRetrievesOwnCompletedRun() {
        String owner = owner();
        SanitizationRunView created = completedRun(owner);

        SanitizationRunView view = runs.get(owner, created.id());

        assertThat(view.id()).isEqualTo(created.id());
        assertThat(view.datasetId()).isEqualTo(created.datasetId());
        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(view.inputRowCount()).isEqualTo(1L);
        assertThat(view.outputRowCount()).isEqualTo(1L);
        assertThat(view.blankRowsSkipped()).isEqualTo(0L);
        assertThat(view.columnCount()).isEqualTo(2);
        assertThat(view.policyName()).isEqualTo("default");
        assertThat(view.policyVersion()).isEqualTo("v1");
        assertThat(view.policySnapshot()).isEqualTo(created.policySnapshot());
        assertThat(view.startedAt()).isNotNull();
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.errorCode()).isNull();
    }

    @Test
    void ownerRetrievesOwnFailedRun() {
        String owner = owner();
        SanitizationRunView created = failedRun(owner);

        SanitizationRunView view = runs.get(owner, created.id());

        assertThat(view.id()).isEqualTo(created.id());
        assertThat(view.status()).isEqualTo(RunStatus.FAILED);
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.errorCode()).isEqualTo("CSV_PARSE_ERROR");
        assertThat(view.errorStage()).isEqualTo("TOKENIZE");
        assertThat(view.errorMessage()).isEqualTo("CSV row 2 has 3 columns but the header has 2.");
        assertThat(view.inputRowCount()).isNull();
    }

    @Test
    void foreignAndMissingRunsBehaveIdentically() {
        String ownerA = owner();
        String ownerB = owner();
        SanitizationRunView foreign = completedRun(ownerA);

        String foreignMessage = null;
        try {
            runs.get(ownerB, foreign.id());
        } catch (SanitizationRunNotFoundException ex) {
            foreignMessage = ex.getMessage();
        }
        String missingMessage = null;
        try {
            runs.get(ownerB, UUID.randomUUID());
        } catch (SanitizationRunNotFoundException ex) {
            missingMessage = ex.getMessage();
        }

        assertThat(foreignMessage).isNotNull();
        assertThat(foreignMessage).isEqualTo(missingMessage);
        assertThat(foreignMessage).isEqualTo("Sanitization run not found.");
    }

    @Test
    void viewExposesNoOwnerSubjectOrSensitiveData() {
        String owner = owner();
        SanitizationRunView completed = completedRun(owner);
        SanitizationRunView failed = failedRun(owner);

        assertThat(Arrays.stream(SanitizationRunView.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .doesNotContain("ownerSubject");

        for (SanitizationRunView view : new SanitizationRunView[] {completed, failed}) {
            assertThat(view.toString()).doesNotContain(owner);
            assertThat(view.toString())
                    .doesNotContain("bob@example.com", "password", "Exception", "at com.aegivault");
        }
    }
}
