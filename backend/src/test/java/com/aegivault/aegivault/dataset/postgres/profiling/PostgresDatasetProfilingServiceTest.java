package com.aegivault.aegivault.dataset.postgres.profiling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.dataset.postgres.PostgresColumn;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresSchema;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryException;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import com.aegivault.aegivault.dataset.postgres.PostgresTableProfileException;
import com.aegivault.aegivault.dataset.postgres.PostgresTableProfiler;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRowReadException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.dataset.profile.DatasetProfileResponse;
import com.aegivault.aegivault.dataset.profile.DatasetProfileService;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure unit tests for {@link PostgresDatasetProfilingService} against mocks: no
 * Spring context, no database, no JDBC.
 *
 * <p><strong>What these prove about the orchestration.</strong> That the binding
 * is the only source of the table name and is looked up owner-scoped; that the
 * owner is trimmed and a blank one rejected; that a stale binding, a discovery
 * failure, a profiler failure, and a missing source all fail with one fixed safe
 * message and persist nothing; that success persists exactly once and returns
 * the re-read profile; that the owner and dataset id reach every composed call
 * unaltered; and that no unrelated capability is reachable from the class.
 *
 * <p>Every synthetic value below is obviously fake and appears only as a mocked
 * count; nothing is logged.
 */
class PostgresDatasetProfilingServiceTest {

    private static final String OWNER = "owner-1";

    private static final String OTHER = "owner-2";

    private static final String SCHEMA = "public";

    private static final String TABLE = "contacts";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    private final PostgresDatasetBindingService bindings = mock(PostgresDatasetBindingService.class);

    private final PostgresSchemaDiscoveryService discovery = mock(PostgresSchemaDiscoveryService.class);

    private final DatasetProfileService profiles = mock(DatasetProfileService.class);

    private final PostgresTableProfiler profiler = mock(PostgresTableProfiler.class);

    private final PostgresDataSource source = stubSource(SCHEMA);

    private static PostgresDataSource stubSource(String schemaName) {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return schemaName;
            }

            @Override
            public Connection openReadOnlyConnection() {
                throw new AssertionError("the discovery service must be mocked, never really connect");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PostgresDatasetProfilingService service() {
        return new PostgresDatasetProfilingService(
                bindings, discovery, profiles, providerOf(source), providerOf(profiler));
    }

    private PostgresDatasetProfilingService serviceWithoutSource() {
        return new PostgresDatasetProfilingService(
                bindings, discovery, profiles, providerOf(null), providerOf(null));
    }

    /** A binding the owner really created, for {@code SCHEMA}.{@code TABLE}. */
    private static PostgresDatasetBinding bound() {
        return new PostgresDatasetBinding(DATASET_ID, OWNER, SCHEMA, TABLE);
    }

    /** The discovered base table the metadata boundary would return. */
    private static PostgresTable discoveredTable() {
        return new PostgresTable(TABLE, List.of(new PostgresColumn("email", 1, "varchar")));
    }

    /** Discovery reports the bound table as a real base table. */
    private void givenDiscoveredTable() {
        when(discovery.discover(source)).thenReturn(new PostgresSchema(SCHEMA, List.of(discoveredTable())));
    }

    /** A profiler result carrying only counts — no values, by construction. */
    private static DatasetProfile profiled() {
        ColumnProfile column = new ColumnProfile("email", 4, 4, 3,
                Map.of(PiiType.EMAIL, 2), Map.of(PiiType.EMAIL, 2.0 / 3.0), java.util.Set.of(PiiType.EMAIL));
        return new DatasetProfile(DATASET_ID, List.of(column), 1, 100);
    }

    @Test
    void aBoundTableIsProfiledPersistedExactlyOnceAndReRead() {
        givenDiscoveredTable();
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(profiler.profile(DATASET_ID, source, discoveredTable())).thenReturn(profiled());
        DatasetProfileResponse persisted = new DatasetProfileResponse(
                DATASET_ID, List.of(), 1, 100);
        when(profiles.getProfile(OWNER, DATASET_ID)).thenReturn(persisted);

        DatasetProfileResponse returned = service().profile(OWNER, DATASET_ID);

        // The response is literally the re-read persisted state, not the
        // in-memory profiler result.
        assertThat(returned).isSameAs(persisted);
        verify(profiles, times(1)).saveProfile(OWNER, DATASET_ID, profiled());
        verify(profiles, times(1)).getProfile(OWNER, DATASET_ID);
    }

    @Test
    void theBoundTableIsProfiledAndNoOtherTableIsConsidered() {
        givenDiscoveredTable();
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(profiler.profile(any(), any(), any())).thenReturn(profiled());
        when(profiles.getProfile(any(), any()))
                .thenReturn(new DatasetProfileResponse(DATASET_ID, List.of(), 1, 100));

        service().profile(OWNER, DATASET_ID);

        // The discovered base table from metadata discovery, which is the bound
        // name and nothing else: no caller-supplied table, no similarly named
        // table, and no pattern or prefix search anywhere in the flow.
        verify(profiler).profile(DATASET_ID, source, discoveredTable());
        verify(discovery, times(1)).discover(source);
    }

    @Test
    void theOwnerAndDatasetIdAreThreadedToEveryComposedCall() {
        givenDiscoveredTable();
        // The padded owner is trimmed before the lookup, so the stub answers the
        // trimmed value; everything downstream then sees that same trimmed owner.
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(profiler.profile(DATASET_ID, source, discoveredTable())).thenReturn(profiled());
        when(profiles.getProfile(OWNER, DATASET_ID))
                .thenReturn(new DatasetProfileResponse(DATASET_ID, List.of(), 1, 100));

        // A padded owner is trimmed once, then used consistently everywhere.
        service().profile("  " + OWNER + "  ", DATASET_ID);

        verify(bindings).get(OWNER, DATASET_ID);
        verify(profiles).saveProfile(OWNER, DATASET_ID, profiled());
        verify(profiles).getProfile(OWNER, DATASET_ID);
    }

    @Test
    void aBlankOrNullOwnerIsRejectedBeforeAnythingElse() {
        PostgresDatasetProfilingService service = service();

        assertThatThrownBy(() -> service.profile(null, DATASET_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.profile("   ", DATASET_ID))
                .isInstanceOf(IllegalArgumentException.class);

        // Rejected up front: no binding lookup, no source contact, no save.
        verifyNoInteractions(bindings, discovery, profiler, profiles);
    }

    @Test
    void aNullDatasetIdIsRejected() {
        assertThatThrownBy(() -> service().profile(OWNER, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(bindings, profiles);
    }

    @Test
    void aMissingOrForeignBindingSurfacesTheBindingsOwnNotFoundSignal() {
        // The binding layer's 404-equivalent propagates untouched: it says
        // nothing about the source, so it cannot be used to probe the database.
        when(bindings.get(OTHER, DATASET_ID))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        assertThatThrownBy(() -> service().profile(OTHER, DATASET_ID))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);

        // The source is never contacted when the owner has no binding.
        verifyNoInteractions(discovery, profiler, profiles);
    }

    @Test
    void aStaleBindingWhoseTableIsGoneFailsSafelyAndPersistsNothing() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        // The bound table is no longer a discovered base table.
        when(discovery.discover(source)).thenReturn(new PostgresSchema(SCHEMA, List.of()));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class)
                .hasMessage(PostgresDatasetProfilingException.MESSAGE);

        // No profiling, no substitution, no partial profile, and above all the
        // binding is not deleted or rewritten by the failure path.
        verifyNoInteractions(profiler);
        verify(profiles, never()).saveProfile(any(), any(), any());
        verify(profiles, never()).getProfile(any(), any());
        verify(bindings, never()).delete(any(), any());
    }

    @Test
    void aStaleBindingDoesNotFallBackToASimilarlyNamedTable() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        // Similar names are discovered, but neither is the bound one and neither
        // may be profiled in its place.
        when(discovery.discover(source)).thenReturn(new PostgresSchema(SCHEMA, List.of(
                new PostgresTable("contacts_archive", List.of(new PostgresColumn("email", 1, "varchar"))),
                new PostgresTable("contacts_backup", List.of(new PostgresColumn("email", 1, "varchar"))))));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class);

        verifyNoInteractions(profiler);
        verify(profiles, never()).saveProfile(any(), any(), any());
    }

    @Test
    void aDiscoveryFailureSurfacesTheSameFixedSafeMessage() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(discovery.discover(source)).thenThrow(new PostgresSchemaDiscoveryException(
                new SQLException("FATAL: no pg_hba.conf entry")));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class)
                // No SQL state, no driver text, no host: the fixed message only.
                .hasMessage(PostgresDatasetProfilingException.MESSAGE);
        verify(profiles, never()).saveProfile(any(), any(), any());
    }

    @Test
    void anUnreachableSourceSurfacesTheSameFixedSafeMessage() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(discovery.discover(source)).thenThrow(new PostgresSourceConnectionException(
                new SQLException("password authentication failed for user \"app\"")));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class)
                .hasMessage(PostgresDatasetProfilingException.MESSAGE);
        verify(profiles, never()).saveProfile(any(), any(), any());
    }

    @Test
    void aProfilerFailurePersistsNothing() {
        givenDiscoveredTable();
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(profiler.profile(any(), any(), any())).thenThrow(new PostgresTableProfileException(
                new IllegalStateException("detector saw synthetic.user@example.invalid")));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class)
                // A row value can never reach the caller through this message.
                .hasMessage(PostgresDatasetProfilingException.MESSAGE)
                .hasMessageNotContaining("synthetic");
        verify(profiles, never()).saveProfile(any(), any(), any());
    }

    @Test
    void aRowReadFailurePersistsNothing() {
        givenDiscoveredTable();
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(profiler.profile(any(), any(), any()))
                .thenThrow(new PostgresTableRowReadException(new SQLException("relation does not exist")));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class)
                .hasMessage(PostgresDatasetProfilingException.MESSAGE);
        verify(profiles, never()).saveProfile(any(), any(), any());
    }

    @Test
    void anUnconfiguredSourceFailsSafelyWithoutContactingAnything() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());

        assertThatThrownBy(() -> serviceWithoutSource().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class)
                .hasMessage(PostgresDatasetProfilingException.MESSAGE);

        verifyNoInteractions(discovery, profiler, profiles);
    }

    @Test
    void aBindingForAnotherSchemaIsRefusedRatherThanRedirected() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(
                new PostgresDatasetBinding(DATASET_ID, OWNER, "reporting", TABLE));

        assertThatThrownBy(() -> service().profile(OWNER, DATASET_ID))
                .isInstanceOf(PostgresDatasetProfilingException.class);

        // No cross-schema browsing and no silent redirect into public.
        verifyNoInteractions(discovery, profiler);
        verify(profiles, never()).saveProfile(any(), any(), any());
    }

    @Test
    void repeatedProfilingReplacesRatherThanDuplicating() {
        givenDiscoveredTable();
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(profiler.profile(any(), any(), any())).thenReturn(profiled());
        when(profiles.getProfile(any(), any()))
                .thenReturn(new DatasetProfileResponse(DATASET_ID, List.of(), 1, 100));
        PostgresDatasetProfilingService service = service();

        service.profile(OWNER, DATASET_ID);
        service.profile(OWNER, DATASET_ID);

        // Replace semantics are the existing profile service's, not a new
        // upsert: two calls, two saves, one profile row per dataset.
        verify(profiles, times(2)).saveProfile(OWNER, DATASET_ID, profiled());
    }

    @Test
    void theServiceExposesOnlyTheOneProfilingOperation() {
        // One internal entry point and nothing else: no bind, delete, preview,
        // export, or sanitize verb, and no table name, SQL string, or JDBC type
        // on the reachable surface.
        assertThat(java.util.Arrays.stream(PostgresDatasetProfilingService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("profile");
        assertThat(java.util.Arrays.stream(PostgresDatasetProfilingService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .map(Class::getSimpleName))
                .containsExactly("String", "UUID");
    }

    @Test
    void theServiceDependsOnTheFlowOnlyAndNoSanitizationOrGatewaySurface() {
        // Dependency direction, checked structurally: the binding service, the
        // discovery service, the profile service, and the two optional source
        // handles. No sanitizer, run executor, artifact store, policy, gateway,
        // Redis, or audit ledger is reachable from here.
        assertThat(java.util.Arrays.stream(PostgresDatasetProfilingService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "PostgresDatasetBindingService",
                        "PostgresSchemaDiscoveryService",
                        "DatasetProfileService",
                        "ObjectProvider",
                        "ObjectProvider");
    }

    @Test
    void theServiceBuildsNoSqlAndHoldsNoConnection() {
        // Structurally unable to reach a statement: no JDBC type, no query
        // method, and no sanitization collaborator anywhere in the class.
        for (var field : PostgresDatasetProfilingService.class.getDeclaredFields()) {
            assertThat(field.getType().getName()).doesNotContain("java.sql");
        }
        for (var method : PostgresDatasetProfilingService.class.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat(parameter.getName()).doesNotContain("java.sql");
                assertThat(parameter.getName()).doesNotContain("Sanitiz");
                assertThat(parameter.getName()).doesNotContain("Gateway");
                assertThat(parameter.getName()).doesNotContain("Audit");
            }
            assertThat(method.getReturnType().getName()).doesNotContain("Sanitiz");
        }
    }

    @Test
    void theSafeMessageCarriesNoSourceDetail() {
        // The one message a caller can see, stated positively.
        assertThat(PostgresDatasetProfilingException.MESSAGE)
                .doesNotContain("jdbc:", "password", "SQLException", "Exception", "http");
    }
}