package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PiiDetectorRegistry} (no Spring context, no I/O).
 */
class PiiDetectorRegistryTest {

    private static PiiDetector stub(PiiType type) {
        return value -> Optional.of(new PiiDetection(type));
    }

    private static PiiDetector emptyStub() {
        return value -> Optional.empty();
    }

    @Test
    void nullInputReturnsEmpty() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(stub(PiiType.EMAIL)));

        assertThat(registry.detect(null)).isEmpty();
    }

    @Test
    void blankInputReturnsEmpty() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(stub(PiiType.EMAIL)));

        assertThat(registry.detect("   ")).isEmpty();
    }

    @Test
    void noDetectorMatchesReturnsEmpty() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(emptyStub(), emptyStub()));

        assertThat(registry.detect("plain value")).isEmpty();
    }

    @Test
    void singleMatchReturnsOneResult() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(stub(PiiType.EMAIL), emptyStub()));

        assertThat(registry.detect("alice@example.com"))
                .containsExactly(new PiiDetection(PiiType.EMAIL));
    }

    @Test
    void multipleMatchesReturnAllResults() {
        PiiDetectorRegistry registry =
                new PiiDetectorRegistry(List.of(stub(PiiType.PHONE), stub(PiiType.EMAIL)));

        assertThat(registry.detect("anything")).containsExactlyInAnyOrder(
                new PiiDetection(PiiType.EMAIL), new PiiDetection(PiiType.PHONE));
    }

    @Test
    void duplicateTypesCollapseToOne() {
        PiiDetectorRegistry registry =
                new PiiDetectorRegistry(List.of(stub(PiiType.EMAIL), stub(PiiType.EMAIL)));

        assertThat(registry.detect("anything")).containsExactly(new PiiDetection(PiiType.EMAIL));
    }

    @Test
    void resultOrderingIsDeterministic() {
        PiiDetectorRegistry first =
                new PiiDetectorRegistry(List.of(stub(PiiType.PHONE), stub(PiiType.EMAIL)));
        PiiDetectorRegistry second =
                new PiiDetectorRegistry(List.of(stub(PiiType.EMAIL), stub(PiiType.PHONE)));

        List<PiiDetection> expected =
                List.of(new PiiDetection(PiiType.EMAIL), new PiiDetection(PiiType.PHONE));
        assertThat(first.detect("anything")).containsExactlyElementsOf(expected);
        assertThat(second.detect("anything")).containsExactlyElementsOf(expected);
    }

    @Test
    void detectorExceptionPropagates() {
        PiiDetector failing = value -> {
            throw new IllegalStateException("boom");
        };
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(failing));

        assertThatThrownBy(() -> registry.detect("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void doesNotModifyDetectorResults() {
        PiiDetection detection = new PiiDetection(PiiType.EMAIL);
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(value -> Optional.of(detection)));

        assertThat(registry.detect("anything")).containsExactly(detection);
    }

    @Test
    void wiresConcreteDetectors() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector(), new CreditCardDetector()));

        assertThat(registry.detect("alice@example.com"))
                .containsExactly(new PiiDetection(PiiType.EMAIL));
        assertThat(registry.detect("9876543210"))
                .containsExactly(new PiiDetection(PiiType.PHONE));
        assertThat(registry.detect("4111111111111111"))
                .containsExactly(new PiiDetection(PiiType.CREDIT_CARD));
        assertThat(registry.detect("plain value")).isEmpty();
    }
}
