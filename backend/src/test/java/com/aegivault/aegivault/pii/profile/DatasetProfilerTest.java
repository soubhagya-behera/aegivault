package com.aegivault.aegivault.pii.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link DatasetProfiler} (no Spring, no I/O). */
class DatasetProfilerTest {

    private DatasetProfiler profiler() {
        PiiColumnProfiler columnProfiler = new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector())));
        return new DatasetProfiler(columnProfiler);
    }

    @Test
    void profilesMultipleColumnsInDeterministicOrder() {
        UUID id = UUID.randomUUID();
        List<ColumnInput> inputs = List.of(
                new ColumnInput("phone", List.of("9876543210", "plain value")),
                new ColumnInput("email", List.of("alice@example.com", "plain value")));
        DatasetProfile profile = profiler().profile(id, inputs);
        assertThat(profile.datasetId()).isEqualTo(id);
        assertThat(profile.totalColumns()).isEqualTo(2);
        assertThat(profile.columns()).extracting("columnName")
                .containsExactly("email", "phone");
        assertThat(profile.maxSampleSizePerColumn())
                .isEqualTo(PiiColumnProfiler.DEFAULT_MAX_SAMPLE_SIZE);
    }

    @Test
    void profilesMixedColumnTypes() {
        DatasetProfile profile = profiler().profile(null, List.of(
                new ColumnInput("email", List.of("alice@example.com")),
                new ColumnInput("notes", List.of("plain value"))));
        assertThat(profile.columns()).hasSize(2);
        assertThat(profile.columns().get(0).detectedTypes()).isNotEmpty();
        assertThat(profile.columns().get(1).detectedTypes()).isEmpty();
    }

    @Test
    void profilesEmptyDataset() {
        DatasetProfile profile = profiler().profile(null, List.of());
        assertThat(profile.totalColumns()).isZero();
        assertThat(profile.columns()).isEmpty();
    }

    @Test
    void resultColumnsAreImmutable() {
        DatasetProfile profile = profiler().profile(null,
                List.of(new ColumnInput("email", List.of("alice@example.com"))));
        assertThat(profile.toString()).doesNotContain("alice@example.com");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> profile.columns().add(profile.columns().get(0)));
    }

    @Test
    void outputIsDeterministic() {
        List<ColumnInput> inputs = List.of(
                new ColumnInput("phone", List.of("9876543210")),
                new ColumnInput("email", List.of("alice@example.com")));
        assertThat(profiler().profile(null, inputs))
                .isEqualTo(profiler().profile(null, inputs));
    }

    @Test
    void reportsDefaultSampleLimit() {
        DatasetProfiler profiler = profiler();
        DatasetProfile profile = profiler.profile(null,
                List.of(new ColumnInput("email", List.of("alice@example.com"))));
        assertThat(profile.maxSampleSizePerColumn()).isEqualTo(100);
    }

    @Test
    void reportsCustomSampleLimitAndRespectsIt() {
        PiiColumnProfiler columnProfiler = new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector())), 3);
        DatasetProfiler profiler = new DatasetProfiler(columnProfiler);
        List<String> values = List.of("alice@example.com", "bob@example.com",
                "carol@example.com", "dave@example.com", "erin@example.com");
        DatasetProfile profile = profiler.profile(
                null, List.of(new ColumnInput("email", values)));
        assertThat(profile.maxSampleSizePerColumn()).isEqualTo(3);
        assertThat(profile.columns()).hasSize(1);
        assertThat(profile.columns().get(0).suppliedValueCount()).isEqualTo(5);
        assertThat(profile.columns().get(0).analyzedValueCount()).isEqualTo(3);
        assertThat(profile.columns().get(0).detectionCounts())
                .containsEntry(PiiType.EMAIL, 3);
        assertThat(profile.toString()).doesNotContain("alice@example.com");
    }

    @Test
    void customLimitProfileRemainsImmutable() {
        PiiColumnProfiler columnProfiler = new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector())), 3);
        DatasetProfiler profiler = new DatasetProfiler(columnProfiler);
        DatasetProfile profile = profiler.profile(
                null, List.of(new ColumnInput("email", List.of("alice@example.com"))));
        assertThat(profile.maxSampleSizePerColumn()).isEqualTo(3);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> profile.columns().add(profile.columns().get(0)));
    }
}
