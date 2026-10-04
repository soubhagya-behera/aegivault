package com.aegivault.aegivault.dataset.postgres.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.postgres.PostgresColumn;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresSchema;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure unit tests for {@link PostgresDatasetBindingService} against mocks: no
 * Spring context, no database, no JDBC.
 *
 * <p>They pin the rules this milestone exists for. Ownership is checked before
 * the source is touched, identifiers are refused before any source lookup, a
 * second binding for the same dataset is rejected, and an absent source or an
 * absent table fail with one safe message that reveals neither the source's
 * contents nor any JDBC detail.
 */
class PostgresDatasetBindingServiceTest {

    private static final String OWNER = "owner-1";

    private static final String OTHER = "owner-2";

    private static final String SCHEMA = "public";

    private static final String TABLE = "customers";

    private PostgresDatasetBindingRepository bindings;

    private DatasetRepository datasets;

    private PostgresSchemaDiscoveryService discovery;

    private PostgresDataSource source;

    private SanitizationRunRepository runs;

    private UUID datasetId;

    @BeforeEach
    void setUp() {
        bindings = mock(PostgresDatasetBindingRepository.class);
        datasets = mock(DatasetRepository.class);
        discovery = mock(PostgresSchemaDiscoveryService.class);
        runs = mock(SanitizationRunRepository.class);
        source = sourceReportingSchema(SCHEMA);
        datasetId = UUID.randomUUID();
        when(datasets.findByIdAndOwnerSubject(datasetId, OWNER))
                .thenReturn(Optional.of(new Dataset("d", OWNER)));
    }

    private PostgresDatasetBindingService service(PostgresDataSource configured) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresDataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configured);
        return new PostgresDatasetBindingService(bindings, datasets, discovery, provider, runs);
    }

    private PostgresDataSource sourceReportingSchema(String schema) {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return schema;
            }

            @Override
            public Connection openReadOnlyConnection() {
                return mock(Connection.class);
            }
        };
    }

    /** Makes discovery report one table as a discovered base table. */
    private void discoveryFinds(String tableName) {
        when(discovery.discover(any())).thenReturn(new PostgresSchema(SCHEMA, List.of(
                new PostgresTable(tableName, List.of(new PostgresColumn("id", 1, "int4"))))));
    }

    @Test
    void aSuccessfulBindStoresTheOwnerSchemaAndTable() {
        discoveryFinds(TABLE);
        when(bindings.save(any(PostgresDatasetBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PostgresDatasetBinding stored = service(source).bind(OWNER, datasetId, SCHEMA, TABLE);

        assertThat(stored.getDatasetId()).isEqualTo(datasetId);
        assertThat(stored.getOwnerSubject()).isEqualTo(OWNER);
        assertThat(stored.getSchemaName()).isEqualTo(SCHEMA);
        assertThat(stored.getTableName()).isEqualTo(TABLE);
        verify(bindings).save(any(PostgresDatasetBinding.class));
    }

    @Test
    void aSuccessfulGetReturnsTheOwnedBinding() {
        PostgresDatasetBinding stored = new PostgresDatasetBinding(datasetId, OWNER, SCHEMA, TABLE);
        when(bindings.findByDatasetIdAndOwnerSubject(datasetId, OWNER)).thenReturn(Optional.of(stored));

        assertThat(service(source).get(OWNER, datasetId)).isSameAs(stored);
    }

    @Test
    void aSuccessfulDeleteRemovesTheOwnedBinding() {
        PostgresDatasetBinding stored = new PostgresDatasetBinding(datasetId, OWNER, SCHEMA, TABLE);
        when(bindings.findByDatasetIdAndOwnerSubject(datasetId, OWNER)).thenReturn(Optional.of(stored));

        service(source).delete(OWNER, datasetId);

        verify(bindings).delete(stored);
    }

    @Test
    void anotherOwnerCanNeitherReadNorDeleteTheBinding() {
        when(bindings.findByDatasetIdAndOwnerSubject(datasetId, OTHER)).thenReturn(Optional.empty());

        PostgresDatasetBindingService service = service(source);
        assertThatThrownBy(() -> service.get(OTHER, datasetId))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        assertThatThrownBy(() -> service.delete(OTHER, datasetId))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        verify(bindings, never()).delete(any());
    }

    @Test
    void aCrossOwnerDatasetCannotBeBound() {
        discoveryFinds(TABLE);

        // The dataset lookup is owner-scoped, so another owner's dataset simply
        // is not there.
        assertThatThrownBy(() -> service(source).bind(OTHER, datasetId, SCHEMA, TABLE))
                .isInstanceOf(DatasetNotFoundException.class);
        verify(bindings, never()).save(any());
    }

    @Test
    void aSecondBindingForTheSameDatasetIsRejected() {
        discoveryFinds(TABLE);
        when(bindings.findByDatasetIdAndOwnerSubject(datasetId, OWNER))
                .thenReturn(Optional.of(new PostgresDatasetBinding(datasetId, OWNER, SCHEMA, TABLE)));

        assertThatThrownBy(() -> service(source).bind(OWNER, datasetId, SCHEMA, "orders"))
                .isInstanceOf(PostgresDatasetAlreadyBoundException.class)
                .hasMessage(PostgresDatasetAlreadyBoundException.MESSAGE);
        verify(bindings, never()).save(any());
    }

    @Test
    void aBlankOwnerIsRejected() {
        PostgresDatasetBindingService service = service(source);

        assertThatThrownBy(() -> service.bind("  ", datasetId, SCHEMA, TABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.get(null, datasetId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(" ", datasetId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidIdentifiersAreRejectedBeforeTheSourceIsConsulted() {
        for (String hostile : new String[] {"users'; DROP TABLE datasets; --", "public.customers",
                "users\"", "users; DROP", "us ers", "users%", "1users", ""}) {
            assertThatThrownBy(() -> service(source).bind(OWNER, datasetId, SCHEMA, hostile))
                    .as("table [%s] must be refused", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service(source).bind(OWNER, datasetId, hostile, TABLE))
                    .as("schema [%s] must be refused", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Nothing was ever asked of the source, so an unusable name cannot be
        // used to probe what it contains.
        verify(discovery, never()).discover(any());
        verify(bindings, never()).save(any());
    }

    @Test
    void aMissingTableFailsWithOneSafeMessage() {
        discoveryFinds("a_different_table");

        assertThatThrownBy(() -> service(source).bind(OWNER, datasetId, SCHEMA, TABLE))
                .isInstanceOf(PostgresDatasetBindingSourceException.class)
                .hasMessage(PostgresDatasetBindingSourceException.MESSAGE)
                // No identifier, no SQL, no credential, no driver text.
                .hasMessageNotContaining(TABLE).hasMessageNotContaining(SCHEMA)
                .hasMessageNotContaining("SELECT").hasMessageNotContaining("jdbc:");
        verify(bindings, never()).save(any());
    }

    @Test
    void anUnconfiguredSourceFailsWithTheSameSafeMessage() {
        // "No source configured" and "no such table" are deliberately
        // indistinguishable, so binding cannot be used to enumerate the source.
        assertThatThrownBy(() -> service(null).bind(OWNER, datasetId, SCHEMA, TABLE))
                .isInstanceOf(PostgresDatasetBindingSourceException.class)
                .hasMessage(PostgresDatasetBindingSourceException.MESSAGE);
        verify(discovery, never()).discover(any());
        verify(bindings, never()).save(any());
    }

    @Test
    void anUnreachableSourceFailsWithTheSameSafeMessage() {
        when(discovery.discover(any()))
                .thenThrow(new PostgresSourceConnectionException(
                        new SQLException("FATAL: connection to jdbc:postgresql://db.internal:5432/prod failed")));

        // The driver text, the URL, and the host stay in the cause for
        // server-side diagnostics and never reach the message.
        PostgresDatasetBindingSourceException failure = org.junit.jupiter.api.Assertions.assertThrows(
                PostgresDatasetBindingSourceException.class,
                () -> service(source).bind(OWNER, datasetId, SCHEMA, TABLE));

        assertThat(failure.getMessage()).isEqualTo(PostgresDatasetBindingSourceException.MESSAGE);
        assertThat(failure.getMessage()).doesNotContain("db.internal", "jdbc:", "prod", "FATAL");
        assertThat(failure.getCause()).isNotNull();
    }

    @Test
    void aSchemaOtherThanTheConfiguredOneIsRefused() {
        discoveryFinds(TABLE);
        PostgresDataSource otherSchemaSource = sourceReportingSchema("reporting_core");

        // The source has one configured schema and no cross-schema browsing, so
        // a binding cannot point at another schema's same-named table.
        assertThatThrownBy(() -> service(otherSchemaSource).bind(OWNER, datasetId, SCHEMA, TABLE))
                .isInstanceOf(PostgresDatasetBindingSourceException.class);
        verify(bindings, never()).save(any());
    }

    @Test
    void aViewOrMaterializedViewIsNotABindableBaseTable() {
        // Discovery only ever reports base tables, so a view, a materialized
        // view, a function, or a procedure simply is not a discovered table.
        when(discovery.discover(any())).thenReturn(new PostgresSchema(SCHEMA, List.of()));

        for (String notABaseTable : new String[] {"reporting_view", "reporting_matview",
                "some_function", "some_procedure"}) {
            assertThatThrownBy(() -> service(source)
                    .bind(OWNER, datasetId, SCHEMA, notABaseTable))
                    .as("[%s] must not be bindable", notABaseTable)
                    .isInstanceOf(PostgresDatasetBindingSourceException.class);
        }
        verify(bindings, never()).save(any());
    }

    @Test
    void aMissingDatasetIdIsRejected() {
        PostgresDatasetBindingService service = service(source);

        assertThatThrownBy(() -> service.bind(OWNER, null, SCHEMA, TABLE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.get(OWNER, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.delete(OWNER, null))
                .isInstanceOf(NullPointerException.class);
    }

    private void givenBindingExists() {
        when(bindings.findByDatasetIdAndOwnerSubject(datasetId, OWNER))
                .thenReturn(Optional.of(new PostgresDatasetBinding(datasetId, OWNER, SCHEMA, TABLE)));
    }

    private void givenActivePostgresRun(boolean active) {
        when(runs.existsByDatasetIdAndOwnerSubjectAndSourceTypeAndStatusIn(
                        eq(datasetId),
                        eq(OWNER),
                        eq(SanitizationSourceType.POSTGRESQL),
                        eq(List.of(RunStatus.QUEUED, RunStatus.RUNNING))))
                .thenReturn(active);
    }

    @Test
    void anActivePostgresRunBlocksDeletionAndLeavesTheBindingIntact() {
        givenBindingExists();
        givenActivePostgresRun(true);

        assertThatThrownBy(() -> service(source).delete(OWNER, datasetId))
                .isInstanceOf(PostgresDatasetBindingActiveRunException.class)
                .hasMessage(PostgresDatasetBindingActiveRunException.MESSAGE)
                .hasMessage("PostgreSQL dataset binding cannot be deleted while a sanitization run is active.");
        verify(bindings, never()).delete(any());
    }

    @Test
    void theGuardCountsPostgresQueuedAndRunningRunsOnly() {
        givenBindingExists();
        givenActivePostgresRun(false);

        // The exact existence query: this dataset, this owner, POSTGRESQL
        // source kind, QUEUED or RUNNING status — so CSV runs and terminal
        // runs can never match.
        service(source).delete(OWNER, datasetId);

        verify(runs).existsByDatasetIdAndOwnerSubjectAndSourceTypeAndStatusIn(
                datasetId, OWNER, SanitizationSourceType.POSTGRESQL,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING));
        verify(bindings).delete(any(PostgresDatasetBinding.class));
    }

    @Test
    void aTerminalPostgresRunDoesNotBlockDeletion() {
        // COMPLETED and FAILED rows are invisible to the guard query, which the
        // repository answers with false; deletion then proceeds normally.
        givenBindingExists();
        givenActivePostgresRun(false);

        service(source).delete(OWNER, datasetId);

        verify(bindings).delete(any(PostgresDatasetBinding.class));
    }

    @Test
    void aForeignBindingIsA404BeforeTheGuardIsEverConsulted() {
        when(bindings.findByDatasetIdAndOwnerSubject(datasetId, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(source).delete(OTHER, datasetId))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        verifyNoInteractions(runs);
        verify(bindings, never()).delete(any());
    }
}
