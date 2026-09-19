package com.aegivault.aegivault.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/**
 * Proves the real persistence path: Flyway V1 migration applies against
 * PostgreSQL, the {@link Dataset} mapping validates, and basic
 * save/retrieve round-trips work. No embedded database is used: replacement
 * is disabled so the test runs against the real local PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DatasetRepositoryTest {

    @Autowired
    private DatasetRepository repository;

    @Test
    void persistAndRetrieveDataset() {
        Dataset dataset = new Dataset("customers.csv", "dev-user");
        dataset.setOriginalFilename("customers.csv");
        dataset.setRowCount(120L);

        Dataset saved = repository.saveAndFlush(dataset);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getSourceType()).isEqualTo("CSV");
        assertThat(saved.getStatus()).isEqualTo("UPLOADED");

        Dataset found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("customers.csv");
        assertThat(found.getRowCount()).isEqualTo(120L);

        List<Dataset> owned = repository.findByOwnerSubject("dev-user");
        assertThat(owned).extracting(Dataset::getId).contains(saved.getId());
    }
}
