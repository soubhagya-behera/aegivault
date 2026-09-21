package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfiler;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the CSV profiling boundary:
 * CSV input → {@link CsvDiscoveryService} → {@code ColumnInput} →
 * {@link DatasetProfiler} → {@link DatasetProfile}.
 *
 * <p>Pure unit tests (no Spring context, no database, no network). All data is
 * synthetic; no real credentials or provider-shaped secrets appear in fixtures.
 */
class CsvDatasetProfilerTest {

    private static final int DEFAULT_SAMPLE_SIZE = PiiColumnProfiler.DEFAULT_MAX_SAMPLE_SIZE;

    private static final List<PiiDetector> DETECTORS = List.of(new EmailDetector(), new PhoneDetector());

    private static CsvDatasetProfiler profiler(int sampleSize) {
        return profiler(CsvLimits.defaults(), sampleSize);
    }

    private static CsvDatasetProfiler profiler(CsvLimits limits, int sampleSize) {
        PiiColumnProfiler columnProfiler = new PiiColumnProfiler(new PiiDetectorRegistry(DETECTORS), sampleSize);
        return new CsvDatasetProfiler(new CsvDiscoveryService(limits), new DatasetProfiler(columnProfiler));
    }

    private static ColumnProfile column(DatasetProfile profile, String columnName) {
        return profile.columns().stream()
                .filter(profileColumn -> profileColumn.columnName().equals(columnName))
                .findFirst()
                .orElseThrow();
    }

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void detectsEmailColumn() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                name,email
                Alice,alice@example.com
                Bob,bob@example.com
                """);
        assertThat(profile.totalColumns()).isEqualTo(2);
        ColumnProfile email = column(profile, "email");
        assertThat(email.detectedTypes()).contains(PiiType.EMAIL);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(email.detectionRates().get(PiiType.EMAIL)).isEqualTo(1.0);
        assertThat(email.analyzedValueCount()).isEqualTo(2);
    }

    @Test
    void detectsPhoneColumn() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                phone
                9876543210
                9123456789
                """);
        ColumnProfile phone = column(profile, "phone");
        assertThat(phone.detectedTypes()).containsExactly(PiiType.PHONE);
        assertThat(phone.detectionCounts()).containsEntry(PiiType.PHONE, 2);
    }

    @Test
    void detectsMixedPiiColumns() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                id,email,phone
                1,alice@example.com,9876543210
                2,bob@example.com,9123456789
                """);
        assertThat(profile.totalColumns()).isEqualTo(3);
        assertThat(column(profile, "email").detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(column(profile, "phone").detectionCounts()).containsEntry(PiiType.PHONE, 2);
        assertThat(column(profile, "id").detectedTypes()).isEmpty();
    }

    @Test
    void detectsNoPiiInPlainColumns() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                notes
                plain value
                office supplies
                """);
        ColumnProfile notes = column(profile, "notes");
        assertThat(notes.detectedTypes()).isEmpty();
        assertThat(notes.detectionCounts()).isEmpty();
        assertThat(notes.analyzableValueCount()).isEqualTo(2);
    }

    @Test
    void treatsBlankValuesAsUnanalyzable() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                name,email
                Alice,
                Bob,bob@example.com
                """);
        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(2);
        assertThat(email.analyzedValueCount()).isEqualTo(2);
        assertThat(email.analyzableValueCount()).isEqualTo(1);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 1);
        assertThat(email.detectionRates().get(PiiType.EMAIL)).isEqualTo(1.0);
    }

    @Test
    void skipsAllBlankRowsBeforeProfiling() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                email


                alice@example.com
                """);
        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(1);
        assertThat(email.analyzedValueCount()).isEqualTo(1);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 1);
    }

    @Test
    void boundsSamplingForLongCsvInput() {
        StringBuilder csv = new StringBuilder("email\n");
        for (int row = 0; row < 250; row++) {
            csv.append("user").append(row).append("@example.com\n");
        }
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, csv.toString());
        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(DEFAULT_SAMPLE_SIZE);
        assertThat(email.analyzedValueCount()).isEqualTo(DEFAULT_SAMPLE_SIZE);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, DEFAULT_SAMPLE_SIZE);
    }

    @Test
    void respectsCustomProfilerSampleLimit() {
        DatasetProfile profile = profiler(2).profileCsv(null, """
                email
                user1@example.com
                user2@example.com
                user3@example.com
                user4@example.com
                """);
        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(2);
        assertThat(email.analyzedValueCount()).isEqualTo(2);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(profile.maxSampleSizePerColumn()).isEqualTo(2);
    }

    @Test
    void reportsConfiguredSampleLimit() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE)
                .profileCsv(null, "email\nalice@example.com\n");
        assertThat(profile.maxSampleSizePerColumn()).isEqualTo(DEFAULT_SAMPLE_SIZE);
    }

    @Test
    void ordersColumnsByColumnNameDeterministically() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                phone,email,name
                9876543210,alice@example.com,Alice
                """);
        assertThat(profile.columns()).extracting(ColumnProfile::columnName)
                .containsExactly("email", "name", "phone");
        assertThat(profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, "phone,email\n9876543210,a@b.co\n"))
                .isEqualTo(profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, "phone,email\n9876543210,a@b.co\n"));
    }

    @Test
    void propagatesDatasetId() {
        UUID datasetId = UUID.randomUUID();
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE)
                .profileCsv(datasetId, "email\nalice@example.com\n");
        assertThat(profile.datasetId()).isEqualTo(datasetId);
    }

    @Test
    void profileNeverContainsRawCsvValues() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, """
                name,email,phone
                Alice,alice@example.com,9876543210
                """);
        assertThat(profile.toString())
                .doesNotContain("Alice")
                .doesNotContain("alice@example.com")
                .doesNotContain("9876543210");
        for (ColumnProfile columnProfile : profile.columns()) {
            assertThat(columnProfile.toString())
                    .doesNotContain("Alice")
                    .doesNotContain("alice@example.com")
                    .doesNotContain("9876543210");
        }
    }

    @Test
    void handlesHeaderOnlyCsv() {
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, "name,email\n");
        assertThat(profile.totalColumns()).isEqualTo(2);
        for (ColumnProfile columnProfile : profile.columns()) {
            assertThat(columnProfile.suppliedValueCount()).isZero();
            assertThat(columnProfile.analyzedValueCount()).isZero();
            assertThat(columnProfile.detectedTypes()).isEmpty();
        }
    }

    @Test
    void rejectsEmptyCsvInput() {
        assertThatThrownBy(() -> profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, ""))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV input is empty");
    }

    @Test
    void rejectsRowsThatDoNotMatchHeaderWidthWithoutEchoingValues() {
        assertThatThrownBy(() -> profiler(DEFAULT_SAMPLE_SIZE)
                .profileCsv(null, "name,email\nalice@example.com\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 2 has 1 columns but the header has 2")
                .hasMessageNotContaining("alice@example.com");
    }

    @Test
    void rejectsDuplicateHeaderNames() {
        assertThatThrownBy(() -> profiler(DEFAULT_SAMPLE_SIZE)
                .profileCsv(null, "email,email\na@b.co,c@d.co\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("duplicates an earlier column name");
    }

    @Test
    void rejectsProfilerSampleLimitAboveCsvSampleCeiling() {
        CsvDatasetProfiler profiler = profiler(new CsvLimits(10, 10, 100, 4096), 20);
        assertThatThrownBy(() -> profiler.profileCsv(null, "email\nalice@example.com\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("Requested CSV sample size 20 exceeds the configured maximum of 10");
    }

    @Test
    void doesNotCloseTheCallerOwnedStream() throws IOException {
        TrackingInputStream input = new TrackingInputStream(stream("email\nalice@example.com\n"));
        DatasetProfile profile = profiler(DEFAULT_SAMPLE_SIZE).profileCsv(null, input);
        assertThat(profile.totalColumns()).isEqualTo(1);
        assertThat(input.closed).isFalse();
    }

    @Test
    void rejectsNullArguments() {
        CsvDatasetProfiler profiler = profiler(DEFAULT_SAMPLE_SIZE);
        assertThatThrownBy(() -> profiler.profileCsv(null, (String) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profiler.profileCsv(null, (InputStream) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CsvDatasetProfiler(null, new DatasetProfiler(
                new PiiColumnProfiler(new PiiDetectorRegistry(DETECTORS)))))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Structural evidence for the documented boundary: the facade depends only
     * on CSV discovery and dataset profiling, so it has no repository, entity
     * manager, cache, AI client, or other external dependency to call.
     */
    @Test
    void dependsOnlyOnCsvDiscoveryAndDatasetProfiling() {
        List<Class<?>> dependencyTypes = new ArrayList<>();
        for (Field field : CsvDatasetProfiler.class.getDeclaredFields()) {
            dependencyTypes.add(field.getType());
        }
        assertThat(dependencyTypes)
                .containsExactlyInAnyOrder(CsvDiscoveryService.class, DatasetProfiler.class);
    }

    /** Stream wrapper that records whether anyone closed the caller-owned stream. */
    private static final class TrackingInputStream extends FilterInputStream {

        private boolean closed;

        TrackingInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }
}
