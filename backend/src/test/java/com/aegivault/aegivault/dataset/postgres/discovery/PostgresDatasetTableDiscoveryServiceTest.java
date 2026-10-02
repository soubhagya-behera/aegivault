package com.aegivault.aegivault.dataset.postgres.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryException;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure unit tests for the discovery service: no Spring context, no database, no
 * JDBC.
 *
 * <p><strong>What these prove.</strong> That ownership is checked owner-scoped
 * before the source is touched, that a foreign and a missing dataset are
 * identical, that an absent source and a failed discovery produce the one safe
 * message, that an empty schema is a normal answer, that the configured schema is
 * the only one returned, that discovery order is passed through unsorted, that no
 * binding is created or required, and that the class depends on nothing that
 * could bind, profile, sanitize, or run anything.
 */
class PostgresDatasetTableDiscoveryServiceTest {

    private static final String OWNER = "owner-1";

    private static final String OTHER = "owner-2";

    private static final String SCHEMA = "public";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000d15c");

    private final DatasetRepository datasets = mock(DatasetRepository.class);

    private final PostgresSchemaDiscoveryService discovery = mock(PostgresSchemaDiscoveryService.class);

    private final PostgresDataSource source = stubSource();

    private PostgresDatasetTableDiscoveryService service() {
        return serviceWith(source);
    }

    private PostgresDatasetTableDiscoveryService serviceWith(PostgresDataSource configured) {
        return new PostgresDatasetTableDiscoveryService(datasets, discovery, providerOf(configured));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static PostgresDataSource stubSource() {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return SCHEMA;
            }

            @Override
            public Connection openReadOnlyConnection() {
                throw new AssertionError("discovery is mocked, the test must never really connect");
            }
        };
    }

    private void givenOwnedDataset() {
        when(datasets.findByIdAndOwnerSubject(DATASET_ID, OWNER))
                .thenReturn(Optional.of(new Dataset("customers", OWNER)));
    }

    private void givenDiscovered(PostgresSchema schema) {
        when(discovery.discover(source)).thenReturn(schema);
    }

    @Test
    void theConfiguredSchemaAndItsTablesAreReturned() {
        givenOwnedDataset();
        givenDiscovered(new PostgresSchema(SCHEMA, List.of(
                new PostgresTable("customers", List.of(
                        new PostgresColumn("id", 1, "uuid"),
                        new PostgresColumn("email", 2, "varchar"))),
                new PostgresTable("orders", List.of(
                        new PostgresColumn("id", 1, "integer"))))));

        PostgresTablesResponse response = service().listTables(OWNER, DATASET_ID);

        assertThat(response.schema()).isEqualTo(SCHEMA);
        assertThat(response.tables()).hasSize(2);
        assertThat(response.tables().get(0).name()).isEqualTo("customers");
        assertThat(response.tables().get(0).columns())
                .extracting(PostgresTablesResponse.PostgresColumnView::name,
                        PostgresTablesResponse.PostgresColumnView::ordinalPosition,
                        PostgresTablesResponse.PostgresColumnView::dataType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("id", 1, "uuid"),
                        org.assertj.core.groups.Tuple.tuple("email", 2, "varchar"));
    }

    @Test
    void anEmptySchemaIsANormalAnswerNotAnError() {
        givenOwnedDataset();
        givenDiscovered(new PostgresSchema(SCHEMA, List.of()));

        PostgresTablesResponse response = service().listTables(OWNER, DATASET_ID);

        assertThat(response.schema()).isEqualTo(SCHEMA);
        assertThat(response.tables()).isEmpty();
    }

    @Test
    void discoveryOrderIsPassedThroughWithoutSorting() {
        givenOwnedDataset();
        // Deliberately not alphabetical: the controller must not invent a policy
        // that could disagree with the row stream's ordinal order.
        givenDiscovered(new PostgresSchema(SCHEMA, List.of(
                new PostgresTable("zebra", List.of(new PostgresColumn("b", 2, "int"),
                        new PostgresColumn("a", 1, "int"))),
                new PostgresTable("alpha", List.of(new PostgresColumn("id", 1, "int"))))));

        PostgresTablesResponse response = service().listTables(OWNER, DATASET_ID);

        assertThat(response.tables()).extracting(PostgresTablesResponse.PostgresTableView::name)
                .containsExactly("zebra", "alpha");
        // Columns stay in the discovered ordinal order they arrived in.
        assertThat(response.tables().get(0).columns())
                .extracting(PostgresTablesResponse.PostgresColumnView::name)
                .containsExactly("b", "a");
    }

    @Test
    void aForeignDatasetIsRefusedBeforeTheSourceIsTouched() {
        when(datasets.findByIdAndOwnerSubject(DATASET_ID, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listTables(OTHER, DATASET_ID))
                .isInstanceOf(DatasetNotFoundException.class);

        // Ownership fails first, so an unauthorised caller learns nothing about
        // whether a source is configured.
        verifyNoInteractions(discovery);
    }

    @Test
    void aMissingDatasetRaisesTheSameSignalAsAForeignOne() {
        when(datasets.findByIdAndOwnerSubject(DATASET_ID, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listTables(OWNER, DATASET_ID))
                .isInstanceOf(DatasetNotFoundException.class)
                .hasMessage(new DatasetNotFoundException().getMessage());
        verifyNoInteractions(discovery);
    }

    @Test
    void anUnconfiguredSourceFailsWithTheOneSafeMessage() {
        givenOwnedDataset();

        assertThatThrownBy(() -> serviceWith(null).listTables(OWNER, DATASET_ID))
                .isInstanceOf(PostgresSourceUnavailableException.class)
                .hasMessage(PostgresSourceUnavailableException.MESSAGE)
                .hasMessageNotContaining("jdbc")
                .hasMessageNotContaining("password");
        verifyNoInteractions(discovery);
    }

    @Test
    void aDiscoveryFailureIsTheSameSafeAnswerAsAnUnconfiguredSource() {
        givenOwnedDataset();
        when(discovery.discover(source))
                .thenThrow(new PostgresSchemaDiscoveryException(
                        new SQLException("FATAL: no pg_hba.conf entry for host 10.0.0.5")));

        assertThatThrownBy(() -> service().listTables(OWNER, DATASET_ID))
                .isInstanceOf(PostgresSchemaDiscoveryUnavailableException.class)
                // Byte-for-byte the same message, so the two are indistinguishable.
                .hasMessage(PostgresSourceUnavailableException.MESSAGE)
                .hasMessageNotContaining("pg_hba")
                .hasMessageNotContaining("10.0.0.5");
    }

    @Test
    void anUnreachableSourceIsAlsoTheSameSafeAnswer() {
        givenOwnedDataset();
        when(discovery.discover(source)).thenThrow(new PostgresSourceConnectionException(
                new SQLException("password authentication failed for user \"app\"")));

        assertThatThrownBy(() -> service().listTables(OWNER, DATASET_ID))
                .isInstanceOf(PostgresSchemaDiscoveryUnavailableException.class)
                .hasMessage(PostgresSourceUnavailableException.MESSAGE)
                .hasMessageNotContaining("password");
    }

    @Test
    void aBlankOrNullOwnerIsRejectedBeforeAnythingIsLookedUp() {
        assertThatThrownBy(() -> service().listTables("  ", DATASET_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().listTables(null, DATASET_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(datasets, discovery);
    }

    @Test
    void theServiceHasNoBindingProfilingSanitizationOrRunDependency() {
        // Discovery only: it cannot bind, profile, sanitize, or run anything,
        // because it holds no collaborator that could.
        assertThat(java.util.Arrays.stream(
                        PostgresDatasetTableDiscoveryService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "DatasetRepository", "PostgresSchemaDiscoveryService", "ObjectProvider");
    }

    @Test
    void theServiceCanStructurallyNeverReadARowOrBuildSql() {
        // No JDBC type and no row type is reachable, so it cannot read a value or
        // issue a statement even if that were wanted.
        for (var field : PostgresDatasetTableDiscoveryService.class.getDeclaredFields()) {
            assertThat(field.getType().getName()).doesNotContain("java.sql");
        }
        for (var method : PostgresDatasetTableDiscoveryService.class.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat(parameter.getName()).doesNotContain("PostgresTableRow");
                assertThat(parameter.getName()).doesNotContain("Sanitization");
                assertThat(parameter.getName()).doesNotContain("Binding");
                assertThat(parameter.getName()).doesNotContain("Profile");
            }
        }
    }
}