package com.aegivault.aegivault.dataset.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.AddressDetector;
import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.CustomIdentifierDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.IpAddressDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PasswordDetector;
import com.aegivault.aegivault.pii.PersonNameDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetection;
import com.aegivault.aegivault.pii.PiiDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.UuidDetector;
import com.aegivault.aegivault.sanitization.DataSanitizationService;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import com.aegivault.aegivault.sanitization.strategy.KeepTransformation;
import com.aegivault.aegivault.sanitization.strategy.MaskTransformation;
import com.aegivault.aegivault.sanitization.strategy.RedactTransformation;
import com.aegivault.aegivault.sanitization.strategy.Sha256HashTransformation;
import com.aegivault.aegivault.sanitization.strategy.SyntheticEmailTransformation;
import com.aegivault.aegivault.sanitization.strategy.SyntheticPhoneTransformation;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import com.aegivault.aegivault.sanitization.strategy.ValueTransformation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link CsvSanitizationService} (no Spring, no database). */
class CsvSanitizationServiceTest {

    static final String VALID_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    static final String OPENAI_KEY = "sk-abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private CsvSanitizationService service;

    static List<PiiDetector> allDetectors() {
        return List.of(new EmailDetector(), new PhoneDetector(), new PersonNameDetector(),
                new AddressDetector(), new CreditCardDetector(), new IpAddressDetector(),
                new UuidDetector(), new ApiKeyDetector(), new PasswordDetector(),
                new JwtDetector(), new CustomIdentifierDetector());
    }

    static List<ValueTransformation> allTransformations() {
        return List.of(new KeepTransformation(), new RedactTransformation(),
                new MaskTransformation(), new SyntheticEmailTransformation(),
                new SyntheticPhoneTransformation(), new Sha256HashTransformation());
    }

    static CsvSanitizationService serviceWith(CsvLimits limits) {
        PiiDetectorRegistry detectors = new PiiDetectorRegistry(allDetectors());
        DataSanitizationService sanitization =
                new DataSanitizationService(new TransformationRegistry(allTransformations()));
        return new CsvSanitizationService(detectors, sanitization, limits);
    }

    static CsvSanitizationService serviceWith(PiiDetectorRegistry detectors, CsvLimits limits) {
        DataSanitizationService sanitization =
                new DataSanitizationService(new TransformationRegistry(allTransformations()));
        return new CsvSanitizationService(detectors, sanitization, limits);
    }

    @BeforeEach
    void setUp() {
        service = serviceWith(CsvLimits.defaults());
    }

    static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    static String sanitizeToString(CsvSanitizationService pipeline, String csv, TransformationPlan plan) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        pipeline.sanitize(stream(csv), output, plan);
        return output.toString(StandardCharsets.UTF_8);
    }

    static TransformationPlan redactAll() {
        List<TransformationRule> rules = new ArrayList<>();
        for (PiiType type : PiiType.values()) {
            rules.add(new TransformationRule(type, TransformationStrategy.REDACT));
        }
        return TransformationPlan.of(rules);
    }

    static List<String> outputRecords(String output) {
        List<String> records = new ArrayList<>();
        new CsvTokenizer(100, 100_000).forEachRecord(output, (row, fields) -> records.add(String.join("|", fields)));
        return records;
    }

    // __MORE1__

    @Test
    void preservesHeaderVerbatim() {
        String out = sanitizeToString(service, "Name,EMAIL, phone \nAlice,alice@example.com,9876543210\n",
                redactAll());
        assertThat(outputRecords(out).get(0)).isEqualTo("Name|EMAIL| phone ");
    }

    @Test
    void transformsDetectedCellsAndKeepsPlainValues() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.PERSON_NAME, TransformationStrategy.REDACT));
        String out = sanitizeToString(service, "name,note\nAlice Johnson,plain note\n", plan);
        assertThat(outputRecords(out)).containsExactly("name|note", "[REDACTED]|plain note");
    }

    // __MORE2__

    @Test
    void preservesEmptyFields() {
        String out = sanitizeToString(service, "note,phone\nhello,\n", redactAll());
        assertThat(outputRecords(out).get(1)).isEqualTo("hello|");
    }

    @Test
    void skipsBlankRowsButCountsThem() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CsvSanitizationResult result =
                service.sanitize(stream("a,b\n1,2\n,,\n   \n3,4\n"), output, redactAll());
        assertThat(result.dataRowsWritten()).isEqualTo(2);
        assertThat(result.blankRowsSkipped()).isEqualTo(2);
        assertThat(result.columnCount()).isEqualTo(2);
        assertThat(outputRecords(output.toString(StandardCharsets.UTF_8))).hasSize(3);
    }

    @Test
    void handlesQuotedCommasEscapedQuotesAndQuotedLineBreaks() {
        String csv = "name,address\nAlice,\"221 Baker Street, London\"\n"
                + "Bob,\"She said \"\"hi\"\" loudly\"\nCarol,\"line one\nline two\"\n";
        List<String> records = outputRecords(sanitizeToString(service, csv, redactAll()));
        assertThat(records).hasSize(4);
        assertThat(records.get(1)).isEqualTo("[REDACTED]|[REDACTED]");
        assertThat(records.get(2)).isEqualTo("[REDACTED]|She said \"hi\" loudly");
        assertThat(records.get(3)).isEqualTo("[REDACTED]|line one\nline two");
    }

    @Test
    void acceptsLfCrlfAndLoneCr() {
        assertThat(outputRecords(sanitizeToString(service, "a,b\n1,2\n", redactAll()))).hasSize(2);
        assertThat(outputRecords(sanitizeToString(service, "a,b\r\n1,2\r\n", redactAll()))).hasSize(2);
        assertThat(outputRecords(sanitizeToString(service, "a,b\r1,2\r", redactAll()))).hasSize(2);
    }

    @Test
    void usesLfTerminatorsOnOutput() {
        String out = sanitizeToString(service, "a,b\r\n1,2\r\n", redactAll());
        assertThat(out).isEqualTo("a,b\n1,2\n");
        assertThat(out).doesNotContain("\r");
    }

    @Test
    void stripsBomFromHeader() {
        String out = sanitizeToString(service, "\uFEFFa,b\n1,2\n", redactAll());
        assertThat(outputRecords(out).get(0)).isEqualTo("a|b");
    }

    // __MORE3__

    @Test
    void transformsEmailPhoneCardAndIpWithDefaultPolicy() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CsvSanitizationResult result = service.sanitizeWithDefaultPolicy(
                stream("email,phone,card,ip\nalice@example.com,9876543210,4111111111111111,192.168.1.1\n"),
                output);
        assertThat(result.dataRowsWritten()).isEqualTo(1);
        String[] cells = outputRecords(output.toString(StandardCharsets.UTF_8)).get(1).split("\\|", -1);
        assertThat(cells[0]).endsWith("@example.invalid").doesNotContain("alice");
        assertThat(cells[1]).doesNotContain("9876543210").matches("[6-9][0-9]{9}");
        assertThat(cells[2]).isEqualTo("************1111");
        assertThat(cells[3]).hasSize(64).doesNotContain("192.168.1.1");
    }

    @Test
    void transformsRemainingTypesWithDefaultPolicy() {
        String csv = "name,address,id,key,secret,token,custom\nAlice Johnson,\"221 Baker Street\","
                + "550e8400-e29b-41d4-a716-446655440000," + OPENAI_KEY + ",password=Secret1234,"
                + VALID_JWT + ",customer_id=CUST-12345\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.sanitizeWithDefaultPolicy(stream(csv), output);
        String[] cells = outputRecords(output.toString(StandardCharsets.UTF_8)).get(1).split("\\|", -1);
        assertThat(cells[0]).isEqualTo("[REDACTED]");
        assertThat(cells[1]).isEqualTo("[REDACTED]");
        assertThat(cells[2]).hasSize(64).doesNotContain("550e8400");
        assertThat(cells[3]).isEqualTo("[REDACTED]");
        assertThat(cells[3]).doesNotContain("abcdefghijklmnopqrstuvwxyz");
        assertThat(cells[4]).hasSize(64).doesNotContain("Secret1234");
        assertThat(cells[5]).isEqualTo("[REDACTED]");
        assertThat(cells[5]).doesNotContain("eyJhbGciOi");
        assertThat(cells[6]).hasSize(64).doesNotContain("CUST-12345");
    }

    // __MORE4__

    @Test
    void honoursExplicitCustomPlanAndKeepOverride() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.KEEP),
                new TransformationRule(PiiType.PERSON_NAME, TransformationStrategy.REDACT));
        String out = sanitizeToString(service, "email,name\nalice@example.com,Alice Johnson\n", plan);
        assertThat(outputRecords(out).get(1)).isEqualTo("alice@example.com|[REDACTED]");
    }

    @Test
    void failsClosedForUnmappedDetectedType() {
        TransformationPlan plan =
                TransformationPlan.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        assertThatThrownBy(() -> sanitizeToString(service, "email,phone\nalice@example.com,9876543210\n", plan))
                .isInstanceOf(MissingTransformationException.class)
                .hasMessageContaining("PHONE");
    }

    @Test
    void failsClosedForMissingStrategyImplementation() {
        PiiDetectorRegistry detectors = new PiiDetectorRegistry(List.of(new EmailDetector()));
        TransformationRegistry registry = new TransformationRegistry(List.of(new KeepTransformation()));
        DataSanitizationService sanitization = new DataSanitizationService(registry);
        CsvSanitizationService pipeline =
                new CsvSanitizationService(detectors, sanitization, CsvLimits.defaults());
        TransformationPlan plan =
                TransformationPlan.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        assertThatThrownBy(() -> sanitizeToString(pipeline, "email\nalice@example.com\n", plan))
                .isInstanceOf(SanitizationException.class)
                .hasMessageContaining("REDACT");
    }

    // __MORE5__

    @Test
    void resolvesMultipleDetectionsByAlphabeticalPiiTypeName() {
        PiiDetector first = value -> Optional.of(new PiiDetection(PiiType.PHONE));
        PiiDetector second = value -> Optional.of(new PiiDetection(PiiType.EMAIL));
        CsvSanitizationService pipeline =
                serviceWith(new PiiDetectorRegistry(List.of(first, second)), CsvLimits.defaults());
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT),
                new TransformationRule(PiiType.PHONE, TransformationStrategy.KEEP));
        assertThat(outputRecords(sanitizeToString(pipeline, "value\nanything\n", plan)).get(1))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void multipleDetectionRuleIgnoresBeanOrder() {
        PiiDetector email = value -> Optional.of(new PiiDetection(PiiType.EMAIL));
        PiiDetector phone = value -> Optional.of(new PiiDetection(PiiType.PHONE));
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT),
                new TransformationRule(PiiType.PHONE, TransformationStrategy.KEEP));
        String firstOut = sanitizeToString(
                serviceWith(new PiiDetectorRegistry(List.of(email, phone)), CsvLimits.defaults()),
                "value\nanything\n", plan);
        String secondOut = sanitizeToString(
                serviceWith(new PiiDetectorRegistry(List.of(phone, email)), CsvLimits.defaults()),
                "value\nanything\n", plan);
        assertThat(firstOut).isEqualTo(secondOut);
        assertThat(outputRecords(firstOut).get(1)).isEqualTo("[REDACTED]");
    }

    @Test
    void repeatedValuesStayRepeatedAndDistinctValuesStayDistinct() {
        String csv = "email,card\nalice@example.com,4111111111111111\n"
                + "alice@example.com,378282246310005\nbob@example.com,4111111111111111\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.sanitizeWithDefaultPolicy(stream(csv), output);
        List<String> records = outputRecords(output.toString(StandardCharsets.UTF_8));
        assertThat(records).hasSize(4);
        String[] first = records.get(1).split("\\|", -1);
        String[] second = records.get(2).split("\\|", -1);
        String[] third = records.get(3).split("\\|", -1);
        assertThat(second[0]).isEqualTo(first[0]);
        assertThat(third[0]).isNotEqualTo(first[0]);
        assertThat(third[1]).isEqualTo(first[1]);
        assertThat(second[1]).isNotEqualTo(first[1]);
    }

    // __MORE6__

    @Test
    void rejectsRowWidthMismatchWithoutLeakingValues() {
        String secret = "alice@example.com";
        assertThatThrownBy(() -> sanitizeToString(service, "a,b\n" + secret + "\n", redactAll()))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("CSV row 2 has 1 columns but the header has 2")
                .hasMessageNotContaining(secret);
    }

    @Test
    void rejectsMalformedQuotingWithoutLeakingValues() {
        String secret = "alice@example.com";
        assertThatThrownBy(() -> sanitizeToString(service, "a\n\"" + secret + "\n", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageNotContaining(secret);
    }

    // __MORE7__

    @Test
    void rejectsBlankDuplicateAndTooManyColumnHeaders() {
        assertThatThrownBy(() -> sanitizeToString(service, "\n1\n", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> sanitizeToString(service, "a,\n1,2\n", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> sanitizeToString(service, "Email,email\n1,2\n", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("duplicates");
        CsvSanitizationService small = serviceWith(new CsvLimits(2, 10, 1_000, 1_048_576));
        assertThatThrownBy(() -> sanitizeToString(small, "a,b,c\n1,2,3\n", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("maximum of 2 columns");
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> sanitizeToString(service, "", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("empty");
    }

    // __MORE8__

    @Test
    void rejectsOverlongFieldAndOversizedInput() {
        CsvSanitizationService smallField = serviceWith(new CsvLimits(10, 10, 4, 1_048_576));
        assertThatThrownBy(() -> sanitizeToString(smallField, "a\nalice@example.com\n", redactAll()))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("maximum length of 4")
                .hasMessageNotContaining("alice@example.com");
        CsvSanitizationService smallInput = serviceWith(new CsvLimits(10, 10, 1_000, 8));
        assertThatThrownBy(() -> sanitizeToString(smallInput, "a,b\n1,2\n3,4\n", redactAll()))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("maximum supported size");
    }

    @Test
    void processesEveryRowBeyondTheDiscoverySampleLimit() {
        StringBuilder csv = new StringBuilder("email\n");
        for (int index = 0; index < 150; index++) {
            csv.append("user").append(index).append("@example.com\n");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CsvSanitizationResult result = service.sanitizeWithDefaultPolicy(stream(csv.toString()), output);
        assertThat(result.dataRowsWritten()).isEqualTo(150);
        assertThat(outputRecords(output.toString(StandardCharsets.UTF_8))).hasSize(151);
    }

    // __MORE9__

    @Test
    void escapesCommasQuotesAndEmptyFields() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.PERSON_NAME, TransformationStrategy.REDACT));
        String out = sanitizeToString(service, "a,b,c,d\nplain,\"has, comma\",\"has \"\"quote\"\"\",\n", plan);
        assertThat(out).isEqualTo("a,b,c,d\nplain,\"has, comma\",\"has \"\"quote\"\"\",\n");
    }

    @Test
    void outputRowWidthAlwaysMatchesHeader() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.sanitizeWithDefaultPolicy(stream("email,note\nalice@example.com,\"hello, world\"\n"), output);
        List<String> records = outputRecords(output.toString(StandardCharsets.UTF_8));
        assertThat(records).hasSize(2);
        for (String record : records) {
            assertThat(record.split("\\|", -1)).hasSize(2);
        }
    }

    @Test
    void exceptionMessagesNeverContainRawValues() {
        String secretEmail = "alice@example.com";
        String secretPhone = "9876543210";
        assertThatThrownBy(() -> sanitizeToString(service,
                        "a,b\n" + secretEmail + "," + secretPhone + ",extra\n", redactAll()))
                .isInstanceOf(CsvParseException.class)
                .hasMessageNotContaining(secretEmail).hasMessageNotContaining(secretPhone);
        TransformationPlan plan =
                TransformationPlan.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        assertThatThrownBy(() -> sanitizeToString(service, "phone\n" + secretPhone + "\n", plan))
                .isInstanceOf(MissingTransformationException.class).hasMessageNotContaining(secretPhone);
    }

    // __MORE10__

    @Test
    void doesNotCloseCallerStreams() {
        TrackingInput input = new TrackingInput("a\n1\n".getBytes(StandardCharsets.UTF_8));
        TrackingOutput output = new TrackingOutput();
        service.sanitize(input, output, redactAll());
        assertThat(input.closed).isFalse();
        assertThat(output.closed).isFalse();
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("a\n1\n");
    }

    @Test
    void rejectsNullArgumentsAndUnreadableInput() {
        TransformationPlan plan = redactAll();
        assertThatThrownBy(() -> service.sanitize(null, new ByteArrayOutputStream(), plan))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.sanitize(stream("a\n1\n"), null, plan))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.sanitize(stream("a\n1\n"), new ByteArrayOutputStream(), null))
                .isInstanceOf(NullPointerException.class);
        InputStream failing = new InputStream() {
            @Override
            public int read(byte[] target, int offset, int length) throws java.io.IOException {
                throw new java.io.IOException("broken");
            }

            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("broken");
            }
        };
        assertThatThrownBy(() -> service.sanitize(failing, new ByteArrayOutputStream(), plan))
                .isInstanceOf(CsvParseException.class).hasMessageContaining("Unable to read CSV input");
    }

    @Test
    void rejectsNullConstructorArguments() {
        PiiDetectorRegistry detectors = new PiiDetectorRegistry(allDetectors());
        DataSanitizationService sanitization =
                new DataSanitizationService(new TransformationRegistry(allTransformations()));
        assertThatThrownBy(() -> new CsvSanitizationService(null, sanitization))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CsvSanitizationService(detectors, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CsvSanitizationService(detectors, sanitization, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void resultRecordRejectsInvalidCounts() {
        assertThatThrownBy(() -> new CsvSanitizationResult(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvSanitizationResult(1, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvSanitizationResult(1, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CsvSanitizationResult(2, 3, 1).dataRowsRead()).isEqualTo(3);
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;

        TrackingInput(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws java.io.IOException {
            closed = true;
        }
    }
}
