package com.aegivault.aegivault.dataset.postgres.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Binding persistence round-trip against real PostgreSQL: a binding saves and
 * reads back verbatim, ownership is enforced, one binding per dataset is a
 * database guarantee, the binding dies with its dataset, and no credential or
 * row data ever reaches the table.
 */
@SpringBootTest
@Transactional
class PostgresDatasetBindingPersistenceTest {

    private static final String OWNER = "binding-owner";

    private static final String OTHER = "binding-other";

    private static final String SCHEMA = "public";

    private static final String TABLE = "customers";

    @Autowired
    private PostgresDatasetBindingRepository bindings;

    @Autowired
    private DatasetRepository datasets;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactions;

    private Dataset datasetFor(String owner) {
        return datasets.save(new Dataset("binding-dataset", owner));
    }

    @Test
    void aBindingPersistsAndReadsBackVerbatim() {
        Dataset dataset = datasetFor(OWNER);

        bindings.save(new PostgresDatasetBinding(dataset.getId(), OWNER, SCHEMA, TABLE));
        entityManager.flush();
        entityManager.clear();

        PostgresDatasetBinding read = bindings
                .findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER).orElseThrow();
        assertThat(read.getDatasetId()).isEqualTo(dataset.getId());
        assertThat(read.getOwnerSubject()).isEqualTo(OWNER);
        assertThat(read.getSchemaName()).isEqualTo(SCHEMA);
        assertThat(read.getTableName()).isEqualTo(TABLE);
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getUpdatedAt()).isNotNull();
    }

    @Test
    void timestampsAreSetOnPersistAndStartEqual() {
        Dataset dataset = datasetFor(OWNER);

        PostgresDatasetBinding binding =
                bindings.save(new PostgresDatasetBinding(dataset.getId(), OWNER, SCHEMA, TABLE));
        entityManager.flush();

        assertThat(binding.getCreatedAt()).isNotNull();
        assertThat(binding.getUpdatedAt()).isEqualTo(binding.getCreatedAt());
    }

    @Test
    void ownerScopingKeepsOneOwnersBindingInvisibleToAnother() {
        Dataset dataset = datasetFor(OWNER);
        bindings.save(new PostgresDatasetBinding(dataset.getId(), OWNER, SCHEMA, TABLE));
        entityManager.flush();

        // Another owner cannot read it, which is what makes a foreign binding
        // indistinguishable from a missing one.
        assertThat(bindings.findByDatasetIdAndOwnerSubject(dataset.getId(), OTHER)).isEmpty();
    }

    @Test
    void anUnknownDatasetHasNoBinding() {
        assertThat(bindings.findByDatasetIdAndOwnerSubject(UUID.randomUUID(), OWNER)).isEmpty();
    }

    @Test
    void atMostOneBindingPerDatasetIsADatabaseGuarantee() {
        UUID datasetId = committedDataset();
        try {
            inNewTransaction(() -> insertRaw(datasetId, OWNER, SCHEMA, TABLE));

            // A second row for the same dataset is refused by the primary key.
            // The insert is issued directly rather than through save(), because
            // saving an entity whose id already exists merges (updates) the first
            // row and never reaches the constraint: the point here is the
            // database's guarantee, not the ORM's behaviour.
            assertThatThrownBy(() -> inNewTransaction(
                    () -> insertRaw(datasetId, OWNER, SCHEMA, "orders")))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("pk_postgres_dataset_bindings");
        } finally {
            deleteCommittedDataset(datasetId);
        }
    }

    @Test
    void aBindingDoesNotOutliveItsDataset() {
        Dataset dataset = datasetFor(OWNER);
        UUID datasetId = dataset.getId();
        bindings.save(new PostgresDatasetBinding(datasetId, OWNER, SCHEMA, TABLE));
        entityManager.flush();

        // ON DELETE CASCADE: deleting the dataset takes its binding with it.
        datasets.delete(dataset);
        entityManager.flush();
        entityManager.clear();

        assertThat(bindings.findByDatasetIdAndOwnerSubject(datasetId, OWNER)).isEmpty();
    }

    @Test
    void aBindingCannotReferenceADatasetThatDoesNotExist() {
        // The foreign key refuses a binding with no dataset behind it.
        assertThatThrownBy(() -> inNewTransaction(
                () -> insertRaw(UUID.randomUUID(), OWNER, SCHEMA, TABLE)))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("fk_postgres_dataset_bindings_dataset");
    }

    /**
     * Runs one failing insert in its own transaction.
     *
     * <p>PostgreSQL aborts the entire transaction after a constraint violation, so
     * a refused row would otherwise poison every later statement. Propagation is
     * REQUIRES_NEW so each attempt really is independent of the test's
     * transaction, and a failed attempt rolls itself back rather than taking the
     * test's transaction with it.
     */
    private void inNewTransaction(Runnable work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                status.setRollbackOnly();
                throw ex;
            }
        });
    }

    /**
     * Commits a dataset so the foreign key can be satisfied.
     *
     * <p>Used only by the constraint tests, which run outside the test's
     * transaction; the caller removes the dataset again in {@code finally}.
     */
    private UUID committedDataset() {
        UUID[] id = new UUID[1];
        inNewTransaction(() -> id[0] = datasets.save(new Dataset("constraint-dataset", OWNER)).getId());
        return id[0];
    }

    /** Removes a committed dataset, and with it any binding rows. */
    private void deleteCommittedDataset(UUID datasetId) {
        inNewTransaction(() -> {
            Dataset dataset = datasets.findById(datasetId).orElse(null);
            if (dataset != null) {
                datasets.delete(dataset);
            }
        });
    }

    /** Inserts a binding row directly, bypassing the ORM's merge behaviour. */
    private void insertRaw(UUID datasetId, String owner, String schema, String table) {
        entityManager.createNativeQuery(
                "INSERT INTO postgres_dataset_bindings "
                        + "(dataset_id, owner_subject, schema_name, table_name) VALUES (?, ?, ?, ?)")
                .setParameter(1, datasetId)
                .setParameter(2, owner)
                .setParameter(3, schema)
                .setParameter(4, table)
                .executeUpdate();
    }

    @Test
    void nonIdentifierNamesAreRefusedByTheDatabaseToo() {
        UUID datasetId = committedDataset();
        try {
            // Defence in depth: the service validates with PostgresIdentifiers
            // first, but the CHECK constraints mean a value that reached the
            // database by another route still cannot carry a quote, dot,
            // semicolon, space, or wildcard.
            for (String hostile : new String[] {"users'; DROP TABLE datasets; --",
                    "public.customers", "users\"", "users; DROP", "us ers", "users%", ""}) {
                assertThatThrownBy(() -> inNewTransaction(
                        () -> insertRaw(datasetId, OWNER, SCHEMA, hostile)))
                        .as("table name [%s] must be refused", hostile)
                        .isInstanceOf(ConstraintViolationException.class)
                        .hasMessageContaining("chk_pg_bindings_table_identifier");
            }
        } finally {
            deleteCommittedDataset(datasetId);
        }
    }

    @Test
    void aNonIdentifierSchemaIsRefusedByTheDatabaseToo() {
        UUID datasetId = committedDataset();
        try {
            assertThatThrownBy(() -> inNewTransaction(
                    () -> insertRaw(datasetId, OWNER, "public.customers", TABLE)))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("chk_pg_bindings_schema_identifier");
        } finally {
            deleteCommittedDataset(datasetId);
        }
    }

    @Test
    void aBlankOwnerIsRefusedByTheDatabaseToo() {
        UUID datasetId = committedDataset();
        try {
            assertThatThrownBy(() -> inNewTransaction(
                    () -> insertRaw(datasetId, "  ", SCHEMA, TABLE)))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("chk_pg_bindings_owner_not_blank");
        } finally {
            deleteCommittedDataset(datasetId);
        }
    }

    @Test
    void theStoredRowCarriesNoCredentialAndNoRowDataColumn() {
        // Structural guarantee: a binding has nowhere to keep a host, a
        // credential, or a value, so none can leak into the table.
        assertThat(java.util.Arrays.stream(PostgresDatasetBinding.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName))
                .containsExactlyInAnyOrder("datasetId", "ownerSubject", "schemaName", "tableName",
                        "createdAt", "updatedAt");
    }
}
