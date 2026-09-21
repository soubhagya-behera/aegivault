package com.aegivault.aegivault.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnInput;
import com.aegivault.aegivault.sanitization.strategy.KeepTransformation;
import com.aegivault.aegivault.sanitization.strategy.MaskTransformation;
import com.aegivault.aegivault.sanitization.strategy.RedactTransformation;
import com.aegivault.aegivault.sanitization.strategy.Sha256HashTransformation;
import com.aegivault.aegivault.sanitization.strategy.SyntheticEmailTransformation;
import com.aegivault.aegivault.sanitization.strategy.SyntheticPhoneTransformation;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import com.aegivault.aegivault.sanitization.strategy.ValueTransformation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link ColumnSanitizer} (no Spring, no I/O). */
class ColumnSanitizerTest {

    private ColumnSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        List<ValueTransformation> transformations = List.of(
                new KeepTransformation(),
                new RedactTransformation(),
                new MaskTransformation(),
                new SyntheticEmailTransformation(),
                new SyntheticPhoneTransformation(),
                new Sha256HashTransformation());
        DataSanitizationService service =
                new DataSanitizationService(new TransformationRegistry(transformations));
        sanitizer = new ColumnSanitizer(service);
    }

    @Test
    void sanitizesEverySampledValueWithTheResolvedStrategy() {
        ColumnInput column = new ColumnInput("email", List.of("alice@example.com", "bob@example.com"));
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        SanitizedColumn sanitized = sanitizer.sanitizeColumn(column, PiiType.EMAIL, plan);
        assertThat(sanitized.columnName()).isEqualTo("email");
        assertThat(sanitized.piiType()).isEqualTo(PiiType.EMAIL);
        assertThat(sanitized.strategy()).isEqualTo(TransformationStrategy.SYNTHETIC_EMAIL);
        assertThat(sanitized.sanitizedValues()).hasSize(2);
        assertThat(sanitized.sanitizedValues())
                .allMatch(value -> value.endsWith("@example.invalid"));
        assertThat(sanitized.sanitizedValues()).doesNotContain("alice@example.com", "bob@example.com");
    }

    @Test
    void identicalSourceValuesMapToIdenticalSanitizedValues() {
        ColumnInput column =
                new ColumnInput("email", List.of("alice@example.com", "alice@example.com"));
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        SanitizedColumn sanitized = sanitizer.sanitizeColumn(column, PiiType.EMAIL, plan);
        assertThat(sanitized.sanitizedValues().get(0))
                .isEqualTo(sanitized.sanitizedValues().get(1));
    }

    @Test
    void preservesOrderAndPassesNullAndBlankValuesThrough() {
        ColumnInput column = new ColumnInput("name", new ArrayList<>(Arrays.asList(null, "", "   ", "Alice")));
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.PERSON_NAME, TransformationStrategy.REDACT));
        SanitizedColumn sanitized = sanitizer.sanitizeColumn(column, PiiType.PERSON_NAME, plan);
        assertThat(sanitized.sanitizedValues()).containsExactly(null, "", "   ", "[REDACTED]");
    }

    @Test
    void neverMutatesTheCallerOwnedColumnInput() {
        List<String> values = new ArrayList<>(List.of("alice@example.com", "bob@example.com"));
        ColumnInput column = new ColumnInput("email", values);
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        sanitizer.sanitizeColumn(column, PiiType.EMAIL, plan);
        assertThat(column.values()).containsExactly("alice@example.com", "bob@example.com");
    }

    @Test
    void failsClosedWhenThePlanDoesNotCoverTheType() {
        ColumnInput column = new ColumnInput("email", List.of("alice@example.com"));
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.PHONE, TransformationStrategy.SYNTHETIC_PHONE));
        assertThatThrownBy(() -> sanitizer.sanitizeColumn(column, PiiType.EMAIL, plan))
                .isInstanceOf(MissingTransformationException.class)
                .hasMessageContaining("EMAIL")
                .hasMessageNotContaining("alice@example.com");
    }

    @Test
    void resultCarriesSanitizedValuesOnlyAndIsImmutable() {
        ColumnInput column = new ColumnInput("card", List.of("4111111111111111"));
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.CREDIT_CARD, TransformationStrategy.MASK));
        SanitizedColumn sanitized = sanitizer.sanitizeColumn(column, PiiType.CREDIT_CARD, plan);
        assertThat(sanitized.sanitizedValues()).containsExactly("************1111");
        assertThat(Arrays.stream(SanitizedColumn.class.getDeclaredFields())
                        .noneMatch(field -> field.getName().toLowerCase().contains("raw")))
                .isTrue();
        assertThatThrownBy(() -> sanitized.sanitizedValues().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(sanitized.valueCount()).isEqualTo(1);
    }

    @Test
    void rejectsNullInputs() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        ColumnInput column = new ColumnInput("email", List.of("alice@example.com"));
        assertThatThrownBy(() -> sanitizer.sanitizeColumn(null, PiiType.EMAIL, plan))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sanitizer.sanitizeColumn(column, null, plan))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sanitizer.sanitizeColumn(column, PiiType.EMAIL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ColumnSanitizer(null)).isInstanceOf(NullPointerException.class);
    }
}
