package com.aegivault.aegivault.dataset.postgres.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import com.aegivault.aegivault.sanitization.policy.PolicyNotFoundException;
import com.aegivault.aegivault.sanitization.policy.PolicyResponse;
import com.aegivault.aegivault.sanitization.policy.SanitizationPolicyService;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunTarget;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLaunchException;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLauncher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Orchestration tests for the PostgreSQL run-creation path: check order,
 * policy freezing, launch hand-off, and the guarantee that a foreign actor
 * cannot queue a run.
 *
 * <p><strong>No Spring context and no database.</strong> Every collaborator is a
 * mock, so no connection and no pool is involved. The collaborators' own
 * owner-scoping is covered by their dedicated tests; what is asserted here is
 * the sequence this service drives and what it deliberately does not do.
 */
class PostgresSanitizationRunRequestServiceTest {

    private static final String OWNER = "pg-run-owner";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000f11e");

    private static final UUID POLICY_ID = UUID.fromString("00000000-0000-0000-0000-00000000f11f");

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000f120");

    private PostgresSanitizationRunService postgresRuns;

    private PostgresDatasetBindingService bindings;

    private SanitizationPolicyService policies;

    private SanitizationRunService runs;

    private SanitizationRunJobLauncher launcher;

    private PostgresSanitizationRunRequestService requests;

    @BeforeEach
    void setUp() {
        postgresRuns = mock(PostgresSanitizationRunService.class);
        bindings = mock(PostgresDatasetBindingService.class);
        policies = mock(SanitizationPolicyService.class);
        runs = mock(SanitizationRunService.class);
        launcher = mock(SanitizationRunJobLauncher.class);
        requests = new PostgresSanitizationRunRequestService(
                postgresRuns, bindings, policies, runs, launcher);
    }

    private static PolicyResponse policy() {
        return new PolicyResponse(
                POLICY_ID,
                "pol",
                "v1",
                null,
                List.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK)),
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static SanitizationRunView queued() {
        return new SanitizationRunView(
                RUN_ID, DATASET_ID, RunStatus.QUEUED, "pol", "v1", "{\"rules\":[]}",
                SanitizationSourceType.POSTGRESQL, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private void givenPolicyAndQueue() {
        when(policies.get(OWNER, POLICY_ID)).thenReturn(policy());
        givenQueueSucceeds();
    }

    private void givenQueueSucceeds() {
        when(bindings.get(anyString(), any(UUID.class)))
                .thenReturn(org.mockito.Mockito.mock(
                        com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding.class));
        when(postgresRuns.queue(anyString(), any(UUID.class), any(TransformationPlan.class),
                anyString(), anyString())).thenReturn(queued());
        when(runs.loadForExecution(RUN_ID))
                .thenReturn(new SanitizationRunTarget(
                        RUN_ID, OWNER, DATASET_ID, RunStatus.QUEUED, "pol", "v1",
                        "{\"rules\":[]}", SanitizationSourceType.POSTGRESQL));
    }

    @Test
    void aQueuedRunIsLaunchedThroughTheExistingLauncherWithThePersistedTarget() {
        givenPolicyAndQueue();

        SanitizationRunView view = requests.queueAndLaunch(OWNER, DATASET_ID, POLICY_ID);

        assertThat(view.id()).isEqualTo(RUN_ID);
        assertThat(view.sourceType()).isEqualTo(SanitizationSourceType.POSTGRESQL);
        assertThat(view.status()).isEqualTo(RunStatus.QUEUED);
        // The launcher receives the run row's own detached target, so the worker
        // acts with the owner recorded at creation rather than a caller's word.
        verify(launcher).launch(any(SanitizationRunTarget.class));
        verify(runs).loadForExecution(RUN_ID);
    }

    @Test
    void thePolicyLabelsAndRulesAreFrozenIntoTheRunAndNeverTakenFromTheRequest() {
        givenPolicyAndQueue();

        requests.queueAndLaunch(OWNER, DATASET_ID, POLICY_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(TransformationPlan.class);
        verify(postgresRuns).queue(eq(OWNER), eq(DATASET_ID), captor.capture(), eq("pol"), eq("v1"));

        // Built server-side from the stored rules: EMAIL is masked.
        assertThat(captor.getValue().strategyFor(PiiType.EMAIL))
                .contains(TransformationStrategy.MASK);
    }

    @Test
    void theBindingIsCheckedBeforeThePolicyIsReadAndTheRunIsQueuedLast() {
        givenPolicyAndQueue();

        requests.queueAndLaunch(OWNER, DATASET_ID, POLICY_ID);

        // The documented order: the binding is proven first, then the policy is
        // read, then the run is created from it, then it is launched. A caller
        // who does not own the binding never reaches the policy table.
        InOrder order = inOrder(bindings, policies, postgresRuns, launcher);
        order.verify(bindings).get(OWNER, DATASET_ID);
        order.verify(policies).get(OWNER, POLICY_ID);
        order.verify(postgresRuns).queue(
                eq(OWNER), eq(DATASET_ID), any(TransformationPlan.class), anyString(), anyString());
        order.verify(launcher).launch(any(SanitizationRunTarget.class));
    }

    @Test
    void aMissingOrForeignBindingNeverReachesThePolicyAndLaunchesNothing() {
        // The binding check refuses before any policy read, so a foreign caller
        // cannot even discover whether a policy id exists.
        when(bindings.get(OWNER, DATASET_ID))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        assertThatThrownBy(() -> requests.queueAndLaunch(OWNER, DATASET_ID, POLICY_ID))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);

        verify(policies, never()).get(anyString(), any(UUID.class));
        verify(postgresRuns, never()).queue(
                anyString(), any(UUID.class), any(TransformationPlan.class), anyString(), anyString());
        verify(launcher, never()).launch(any());
    }

    @Test
    void aForeignPolicyIsRefusedAndNoRunIsCreatedOrLaunched() {
        givenQueueSucceeds();
        when(policies.get(OWNER, POLICY_ID)).thenThrow(new PolicyNotFoundException());

        assertThatThrownBy(() -> requests.queueAndLaunch(OWNER, DATASET_ID, POLICY_ID))
                .isInstanceOf(PolicyNotFoundException.class);

        // A foreign actor cannot consume the policy, and no run row is created.
        verify(postgresRuns, never()).queue(
                anyString(), any(UUID.class), any(TransformationPlan.class), anyString(), anyString());
        verify(launcher, never()).launch(any());
    }

    @Test
    void aRefusedSubmissionIsReportedAndNoRecoveryIsAttempted() {
        givenPolicyAndQueue();
        org.mockito.Mockito.doThrow(new SanitizationRunJobLaunchException(
                        new RejectedExecutionException("queue full")))
                .when(launcher).launch(any());

        // The launch failure is not swallowed, and nothing is retried or
        // otherwise papered over.
        assertThatThrownBy(() -> requests.queueAndLaunch(OWNER, DATASET_ID, POLICY_ID))
                .isInstanceOf(SanitizationRunJobLaunchException.class)
                .hasMessage(SanitizationRunJobLaunchException.MESSAGE);

        // Exactly one submission attempt: no retry loop and no second launch.
        verify(launcher, times(1)).launch(any(SanitizationRunTarget.class));
    }

    @Test
    void theServiceHoldsOnlyExistingCollaboratorsAndNoPoolOrSourceOfItsOwn() {
        // The orchestrator adds no executor, no pool, no queue, and no job
        // table: run creation, persistence, and execution all stay with the
        // existing services it delegates to.
        assertThat(java.util.Arrays.stream(
                        PostgresSanitizationRunRequestService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "PostgresSanitizationRunService",
                        "PostgresDatasetBindingService",
                        "SanitizationPolicyService",
                        "SanitizationRunService",
                        "SanitizationRunJobLauncher");
    }
}