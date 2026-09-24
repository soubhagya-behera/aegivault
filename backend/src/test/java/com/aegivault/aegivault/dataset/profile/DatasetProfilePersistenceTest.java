package com.aegivault.aegivault.dataset.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnInput;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfiler;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile persistence round-trip against real PostgreSQL: a profiler
 * result saves and reads back verbatim, replacement leaves no stale rows,
 * ownership is enforced, and no raw values ever reach the tables.
 */
@SpringBootTest
@Transactional
class DatasetProfilePersistenceTest {

    private static final String OWNER = "profile-owner";

    private static final String OTHER = "profile-other";

    @Autowired
    private DatasetProfileService profiles;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private StoredDatasetProfileRepository stored;

    @PersistenceContext
    private EntityManager entities;

    private static DatasetProfiler profiler() {
        return new DatasetProfiler(
                new PiiColumnProfiler(new PiiDetectorRegistry(List.of(new EmailDetector()))));
    }

    private UUID createDataset(String owner) {
        Dataset dataset = new Dataset("customers.csv", owner);
        return datasets.saveAndFlush(dataset).getId();
    }

    private static DatasetProfile twoColumnProfile(UUID datasetId) {
        return profiler().profile(datasetId, List.of(
                new ColumnInput("notes", List.of("hello", "world")),
                new ColumnInput("email", List.of("alice@example.com", "bob@example.com", "not an email"))));
    }

    private long columnRowCount(UUID datasetId) {
        return ((Number) entities
                        .createNativeQuery(
                                "SELECT count(*) FROM dataset_profile_columns WHERE dataset_id = '" + datasetId + "'")
                        .getSingleResult())
                .longValue();
    }

    private long detectionRowCount(UUID datasetId) {
        return ((Number) entities
                        .createNativeQuery(
                                "SELECT count(*) FROM dataset_profile_detections WHERE dataset_id = '"
                                        + datasetId + "'")
                        .getSingleResult())
                .longValue();
    }

    @Test
    void roundTripPreservesCountsRatesAndTypes() {
        UUID datasetId = createDataset(OWNER);
        DatasetProfile saved = twoColumnProfile(datasetId);

        profiles.saveProfile(OWNER, datasetId, saved);
        DatasetProfileResponse read = profiles.getProfile(OWNER, datasetId);

        assertThat(read.datasetId()).isEqualTo(datasetId);
        assertThat(read.totalColumns()).isEqualTo(2);
        assertThat(read.maxSampleSizePerColumn())
                .isEqualTo(PiiColumnProfiler.DEFAULT_MAX_SAMPLE_SIZE);
        assertThat(read.columns()).extracting("columnName").containsExactly("email", "notes");

        DatasetProfileResponse.ColumnProfileResponse email = read.columns().get(0);
        assertThat(email.suppliedValueCount()).isEqualTo(3);
        assertThat(email.analyzedValueCount()).isEqualTo(3);
        assertThat(email.analyzableValueCount()).isEqualTo(3);
        assertThat(email.detectionCounts()).containsExactlyEntriesOf(
                java.util.Map.of(PiiType.EMAIL, 2));
        assertThat(email.detectionRates().get(PiiType.EMAIL)).isCloseTo(2.0 / 3.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(email.detectedTypes()).containsExactly(PiiType.EMAIL);

        DatasetProfileResponse.ColumnProfileResponse notes = read.columns().get(1);
        assertThat(notes.suppliedValueCount()).isEqualTo(2);
        assertThat(notes.analyzedValueCount()).isEqualTo(2);
        assertThat(notes.analyzableValueCount()).isEqualTo(2);
        assertThat(notes.detectionCounts()).isEmpty();
        assertThat(notes.detectionRates()).isEmpty();
        assertThat(notes.detectedTypes()).isEmpty();
    }

    @Test
    void columnOrderingIsDeterministicRegardlessOfInputOrder() {
        UUID datasetId = createDataset(OWNER);
        DatasetProfile profile = profiler().profile(datasetId, List.of(
                new ColumnInput("zeta", List.of("plain")),
                new ColumnInput("alpha", List.of("plain"))));

        profiles.saveProfile(OWNER, datasetId, profile);

        assertThat(profiles.getProfile(OWNER, datasetId).columns())
                .extracting("columnName")
                .containsExactly("alpha", "zeta");
    }

    @Test
    void replacingProfileLeavesNoStaleColumnOrDetectionRows() {
        UUID datasetId = createDataset(OWNER);
        profiles.saveProfile(OWNER, datasetId, profiler().profile(datasetId, List.of(
                new ColumnInput("a", List.of("alice@example.com")),
                new ColumnInput("b", List.of("plain")),
                new ColumnInput("c", List.of("plain")))));
        assertThat(columnRowCount(datasetId)).isEqualTo(3);

        profiles.saveProfile(
                OWNER, datasetId, profiler().profile(datasetId, List.of(
                        new ColumnInput("solo", List.of("alice@example.com")))));

        assertThat(columnRowCount(datasetId)).isEqualTo(1);
        assertThat(detectionRowCount(datasetId)).isEqualTo(1);
        DatasetProfileResponse read = profiles.getProfile(OWNER, datasetId);
        assertThat(read.columns()).extracting("columnName").containsExactly("solo");
        assertThat(read.columns().get(0).detectionCounts())
                .containsExactlyEntriesOf(java.util.Map.of(PiiType.EMAIL, 1));
    }

    @Test
    void saveForForeignDatasetThrowsAndStoresNothing() {
        UUID datasetId = createDataset(OWNER);
        DatasetProfile profile = twoColumnProfile(datasetId);

        assertThatThrownBy(() -> profiles.saveProfile(OTHER, datasetId, profile))
                .isInstanceOf(DatasetNotFoundException.class);

        assertThat(stored.findById(datasetId)).isEmpty();
    }

    @Test
    void getForMissingForeignOrUnprofiledDatasetThrowsNotFound() {
        UUID datasetId = createDataset(OWNER);

        assertThatThrownBy(() -> profiles.getProfile(OTHER, datasetId))
                .isInstanceOf(DatasetProfileNotFoundException.class);
        assertThatThrownBy(() -> profiles.getProfile(OWNER, UUID.randomUUID()))
                .isInstanceOf(DatasetProfileNotFoundException.class);
        assertThatThrownBy(() -> profiles.getProfile(OWNER, datasetId))
                .isInstanceOf(DatasetProfileNotFoundException.class);
    }

    @Test
    void storedRowsAndViewsCarryNoRawValues() {
        UUID datasetId = createDataset(OWNER);
        DatasetProfile profile = twoColumnProfile(datasetId);
        profiles.saveProfile(OWNER, datasetId, profile);

        DatasetProfileResponse read = profiles.getProfile(OWNER, datasetId);
        assertThat(read.toString())
                .doesNotContain("alice@example.com", "bob@example.com", "not an email");
        assertThat(stored.findById(datasetId).orElseThrow().toString())
                .doesNotContain("alice@example.com");

        @SuppressWarnings("unchecked")
        java.util.function.Function<String, List<String>> tableColumns = table -> (List<String>) entities
                .createNativeQuery(
                        "SELECT lower(column_name) FROM information_schema.columns"
                                + " WHERE table_name = '" + table + "' ORDER BY 1")
                .getResultList();
        // Exact metadata-only shape: ids, names, counts, enum names, rates.
        // No value/sample/content column exists by design, so raw CSV values,
        // samples, and PII values have nowhere to be stored.
        assertThat(tableColumns.apply("dataset_profiles")).containsExactly(
                "created_at", "dataset_id", "max_sample_size_per_column", "owner_subject",
                "total_columns", "updated_at");
        assertThat(tableColumns.apply("dataset_profile_columns")).containsExactly(
                "analyzable_value_count", "analyzed_value_count", "column_name", "column_ordinal",
                "dataset_id", "supplied_value_count");
        assertThat(tableColumns.apply("dataset_profile_detections")).containsExactly(
                "column_ordinal", "dataset_id", "detection_count", "detection_rate", "pii_type");
    }
}
