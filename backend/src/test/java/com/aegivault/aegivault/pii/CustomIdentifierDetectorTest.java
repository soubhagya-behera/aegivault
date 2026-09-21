package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CustomIdentifierDetector} (no Spring context, no I/O).
 */
class CustomIdentifierDetectorTest {

    private final PiiDetector detector = new CustomIdentifierDetector();

    @Test
    void detectsCustomerId() {
        assertThat(detector.detect("customer_id=CUST-12345"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsEmployeeId() {
        assertThat(detector.detect("employee_id=EMP-9876"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsMemberId() {
        assertThat(detector.detect("member_id=MBR-1122"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsAccountId() {
        assertThat(detector.detect("account_id=ACC-4455"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsUserId() {
        assertThat(detector.detect("user_id=USR-7788"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsCustomId() {
        assertThat(detector.detect("custom_id=XYZ-9999"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsColonSeparator() {
        assertThat(detector.detect("customer_id:CUST-12345"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void detectsHyphenatedLabel() {
        assertThat(detector.detect("customer-id=CUST-12345"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void labelMatchingIsCaseInsensitive() {
        assertThat(detector.detect("CUSTOMER_ID=CUST-12345"))
                .contains(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }

    @Test
    void rejectsEmptyValue() {
        assertThat(detector.detect("customer_id=")).isEmpty();
    }

    @Test
    void rejectsTooShortValue() {
        assertThat(detector.detect("customer_id=AB")).isEmpty();
    }

    @Test
    void rejectsUnlabelledIdentifier() {
        assertThat(detector.detect("CUST-12345")).isEmpty();
    }

    @Test
    void rejectsUnlabelledUuid() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsUnlabelledOrderId() {
        assertThat(detector.detect("ORD-2024-987654321")).isEmpty();
    }

    @Test
    void rejectsUnsupportedLabel() {
        assertThat(detector.detect("order_id=ORD-12345")).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(detector.detect(null)).isEmpty();
    }

    @Test
    void rejectsBlank() {
        assertThat(detector.detect("   ")).isEmpty();
    }

    @Test
    void resultTypeIsCustomIdentifier() {
        assertThat(detector.detect("customer_id=CUST-12345"))
                .map(PiiDetection::type)
                .contains(PiiType.CUSTOM_IDENTIFIER);
    }

    @Test
    void resultDoesNotContainRawIdentifier() {
        assertThat(detector.detect("customer_id=CUST-12345").orElseThrow().toString())
                .doesNotContain("CUST-12345");
    }
}
