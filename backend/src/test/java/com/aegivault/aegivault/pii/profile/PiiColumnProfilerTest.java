package com.aegivault.aegivault.pii.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link PiiColumnProfiler} (no Spring, no I/O). */
class PiiColumnProfilerTest {

    private PiiColumnProfiler profiler() {
        return new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector())));
    }
    @Test
    void profilesEmailHeavyColumn() {
        ColumnProfile p = profiler().profile(new ColumnInput(
                "email", List.of("alice@example.com", "bob@example.com", "plain value")));
        assertThat(p.detectedTypes()).containsExactly(PiiType.EMAIL);
        assertThat(p.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(p.detectionRates().get(PiiType.EMAIL)).isEqualTo(2.0 / 3.0);
        assertThat(p.suppliedValueCount()).isEqualTo(3);
        assertThat(p.analyzedValueCount()).isEqualTo(3);
        assertThat(p.analyzableValueCount()).isEqualTo(3);
    }

    @Test
    void profilesMixedPiiColumn() {
        ColumnProfile p = profiler().profile(new ColumnInput("mixed",
                Arrays.asList("alice@example.com", "alice@example.com", "plain value", "9876543210")));
        assertThat(p.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(p.detectionCounts()).containsEntry(PiiType.PHONE, 1);
        assertThat(p.detectionRates().get(PiiType.EMAIL)).isEqualTo(0.5);
        assertThat(p.detectionRates().get(PiiType.PHONE)).isEqualTo(0.25);
    }

    @Test
    void profilesNoPiiColumn() {
        ColumnProfile p = profiler().profile(
                new ColumnInput("notes", List.of("plain value", "office supplies")));
        assertThat(p.detectedTypes()).isEmpty();
        assertThat(p.detectionCounts()).isEmpty();
        assertThat(p.detectionRates()).isEmpty();
        assertThat(p.analyzableValueCount()).isEqualTo(2);
    }

    @Test
    void handlesNullsAndBlanks() {
        List<String> values = new ArrayList<>();
        values.add(null);
        values.add("   ");
        values.add("");
        values.add("alice@example.com");
        ColumnProfile p = profiler().profile(new ColumnInput("email", values));
        assertThat(p.suppliedValueCount()).isEqualTo(4);
        assertThat(p.analyzedValueCount()).isEqualTo(4);
        assertThat(p.analyzableValueCount()).isEqualTo(1);
        assertThat(p.detectionCounts()).containsEntry(PiiType.EMAIL, 1);
        assertThat(p.detectionRates().get(PiiType.EMAIL)).isEqualTo(1.0);
    }

    @Test
    void countsDuplicateValues() {
        ColumnProfile p = profiler().profile(new ColumnInput(
                "email", List.of("alice@example.com", "alice@example.com", "alice@example.com")));
        assertThat(p.detectionCounts()).containsEntry(PiiType.EMAIL, 3);
        assertThat(p.detectionRates().get(PiiType.EMAIL)).isEqualTo(1.0);
    }

    @Test
    void boundsSampleSize() {
        PiiColumnProfiler bounded = new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector())), 3);
        List<String> values = List.of("alice@example.com", "bob@example.com",
                "carol@example.com", "dave@example.com", "erin@example.com");
        ColumnProfile p = bounded.profile(new ColumnInput("email", values));
        assertThat(p.suppliedValueCount()).isEqualTo(5);
        assertThat(p.analyzedValueCount()).isEqualTo(3);
        assertThat(p.analyzableValueCount()).isEqualTo(3);
        assertThat(p.detectionCounts()).containsEntry(PiiType.EMAIL, 3);
    }

    @Test
    void profilesEmptyColumn() {
        ColumnProfile p = profiler().profile(new ColumnInput("empty", List.of()));
        assertThat(p.suppliedValueCount()).isZero();
        assertThat(p.analyzedValueCount()).isZero();
        assertThat(p.analyzableValueCount()).isZero();
        assertThat(p.detectedTypes()).isEmpty();
        assertThat(p.detectionCounts()).isEmpty();
        assertThat(p.detectionRates()).isEmpty();
    }

    @Test
    void resultMapsAreImmutable() {
        ColumnProfile p = profiler().profile(
                new ColumnInput("email", List.of("alice@example.com")));
        assertThat(p.toString()).doesNotContain("alice@example.com");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> p.detectionCounts().put(PiiType.PHONE, 1));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> p.detectionRates().put(PiiType.PHONE, 0.5));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> p.detectedTypes().add(PiiType.PHONE));
    }

    @Test
    void outputIsDeterministic() {
        ColumnInput input = new ColumnInput(
                "mixed", Arrays.asList("alice@example.com", "9876543210", "plain value"));
        assertThat(profiler().profile(input)).isEqualTo(profiler().profile(input));
    }

    @Test
    void rejectsNullInput() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> profiler().profile(null));
    }
}
