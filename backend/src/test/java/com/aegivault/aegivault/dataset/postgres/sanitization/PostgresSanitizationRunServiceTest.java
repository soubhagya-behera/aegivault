package com.aegivault.aegivault.dataset.postgres.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import com.aegivault.aegivault.sanitization.run.PolicySnapshot;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationContentSource;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure unit tests for PostgreSQL run creation and dispatch: no Spring context,
 * no database, no JDBC.
 *
 * <p><strong>What these prove.</strong> That a queued PostgreSQL run records the
 * explicit source kind rather than an inferred one, that the owner is threaded
 * and scoped, that the policy snapshot is frozen at creation, that a missing
 * binding prevents a run from being created at all, that this provider never
 * re-resolves a policy or trusts a caller, and that no credential or source
 * location is passed anywhere.
 */
class PostgresSanitizationRunServiceTest {

    private static final String OWNER = "owner-1";

    private static final String OTHER = "owner-2";

    private static final String SCHEMA = "public";

    private static final String TABLE = "contacts";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    private final SanitizationRunService runs = mock(SanitizationRunService.class);

    private final PostgresDatasetBindingService bindings = mock(PostgresDatasetBindingService.class);

    private final PostgresDatasetSanitizationService sanitization =
            mock(PostgresDatasetSanitizationService.class);

    private final TransformationPlan plan = TransformationPlan.of(
            new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));

    private PostgresSanitizationRunService service() {
        return new PostgresSanitizationRunService(runs, bindings, providerOf(sanitization));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private SanitizationRunView view(RunStatus status) {
        return new SanitizationRunView(
                UUID.randomUUID(), DATASET_ID, status, "pol", "v1", "{}", SanitizationSourceType.POSTGRESQL,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void queueingARunRecordsTheExplicitPostgresqlSourceKind() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(runs.createRun(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(view(RunStatus.QUEUED));

        SanitizationRunView queued = service().queue(OWNER, DATASET_ID, plan, "pol", "v1");

        assertThat(queued.status()).isEqualTo(RunStatus.QUEUED);
        // The source kind is stated, never inferred, and the run stays QUEUED:
        // nothing is executed by creation.
        verify(runs).createRun(
                OWNER, DATASET_ID, plan, "pol", "v1", SanitizationSourceType.POSTGRESQL);
    }

    @Test
    void queueingResolvesTheBindingOwnerScopedBeforeAnyRunExists() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(runs.createRun(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(view(RunStatus.QUEUED));

        service().queue(OWNER, DATASET_ID, plan, "pol", "v1");

        verify(bindings).get(OWNER, DATASET_ID);
    }

    @Test
    void aMissingBindingPreventsTheRunFromBeingCreated() {
        when(bindings.get(OWNER, DATASET_ID))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        assertThatThrownBy(() -> service().queue(OWNER, DATASET_ID, plan, "pol", "v1"))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);

        // No run row is created for a dataset with no usable binding.
        verify(runs, never()).createRun(anyString(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void anotherOwnerCannotQueueAgainstThisDataset() {
        when(bindings.get(OTHER, DATASET_ID))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        assertThatThrownBy(() -> service().queue(OTHER, DATASET_ID, plan, "pol", "v1"))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        verify(runs, never()).createRun(anyString(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void thePolicySnapshotIsFrozenAtCreationAndNotReResolved() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(runs.createRun(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(view(RunStatus.QUEUED));

        service().queue(OWNER, DATASET_ID, plan, "pol", "v1");

        // The plan is handed to creation once, where the existing PolicySnapshot
        // freezes it. Nothing here reads a policy afterwards.
        verify(runs, times(1)).createRun(
                eq(OWNER), eq(DATASET_ID), eq(plan), eq("pol"), eq("v1"), any());
        verify(sanitization, never()).contentSourceFor(anyString(), any(), any());
    }

    @Test
    void theProviderIsThePostgresqlSourceKind() {
        assertThat(service().sourceType()).isEqualTo(SanitizationSourceType.POSTGRESQL);
    }

    @Test
    void contentForPassesOnlyTheRunsOwnOwnerDatasetAndPlan() {
        when(sanitization.contentSourceFor(anyString(), any(), any()))
                .thenAnswer(invocation -> mock(SanitizationContentSource.class));

        service().contentFor(OWNER, DATASET_ID, plan);

        // Exactly the run's own values: no schema, table, or credential anywhere.
        verify(sanitization).contentSourceFor(OWNER, DATASET_ID, plan);
    }

    @Test
    void creationAndDispatchCarryNoCredentialOrSourceLocation() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(runs.createRun(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(view(RunStatus.QUEUED));
        when(sanitization.contentSourceFor(anyString(), any(), any()))
                .thenAnswer(invocation -> mock(SanitizationContentSource.class));

        PostgresSanitizationRunService service = service();
        service.queue(OWNER, DATASET_ID, plan, "pol", "v1");
        service.contentFor(OWNER, DATASET_ID, plan);

        // The only string the run path ever supplies is the owner it was given;
        // no host, database, schema, table, URL, or password is passed along.
        verify(runs).createRun(
                eq(OWNER), eq(DATASET_ID), eq(plan), eq("pol"), eq("v1"), eq(SanitizationSourceType.POSTGRESQL));
        verify(sanitization).contentSourceFor(eq(OWNER), eq(DATASET_ID), eq(plan));
    }

    @Test
    void theOwnerIsHandedToTheOwnerScopedBindingLookupSoItIsValidatedThere() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(runs.createRun(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(view(RunStatus.QUEUED));

        // The owner is handed to the owner-scoped lookup as given; trimming and
        // blank-owner validation are that service's job, not this one's, so the
        // same refusals apply here as on every other binding call.
        when(bindings.get("  ", DATASET_ID)).thenThrow(new IllegalArgumentException("ownerSubject must not be blank"));
        assertThatThrownBy(() -> service().queue("  ", DATASET_ID, plan, "pol", "v1"))
                .isInstanceOf(IllegalArgumentException.class);

        service().queue("  " + OWNER + "  ", DATASET_ID, plan, "pol", "v1");
        verify(bindings).get("  " + OWNER + "  ", DATASET_ID);
        // And the owner the run is created with is the same one, unchanged.
        verify(runs).createRun(
                "  " + OWNER + "  ", DATASET_ID, plan, "pol", "v1", SanitizationSourceType.POSTGRESQL);
    }

    @Test
    void missingPlanAndDatasetAreRejectedBeforeAnythingIsLookedUp() {
        assertThatThrownBy(() -> service().queue(OWNER, DATASET_ID, null, "pol", "v1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service().queue(OWNER, null, plan, "pol", "v1"))
                .isInstanceOf(NullPointerException.class);
        org.mockito.Mockito.verifyNoInteractions(bindings, runs);
    }

    @Test
    void theServiceHasNoLifecycleThreadingOrStorageDependency() {
        // Dependency direction: the run service, the binding service, and a lazy
        // handle on the sanitization service. No artifact store, no job launcher,
        // no executor, no thread pool, no audit ledger.
        assertThat(java.util.Arrays.stream(PostgresSanitizationRunService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "SanitizationRunService",
                        "PostgresDatasetBindingService",
                        "ObjectProvider");
    }

    private static PostgresDatasetBinding bound() {
        return new PostgresDatasetBinding(DATASET_ID, OWNER, SCHEMA, TABLE);
    }
}