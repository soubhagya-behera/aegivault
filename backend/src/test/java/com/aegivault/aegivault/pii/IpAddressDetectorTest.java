package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link IpAddressDetector} (no Spring context, no I/O, no network).
 */
class IpAddressDetectorTest {

    private static final String IPV4 = "192.168.1.1";

    private final PiiDetector detector = new IpAddressDetector();

    @Test
    void detectsPrivateIpv4() {
        assertThat(detector.detect("192.168.1.1"))
                .contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsLoopbackIpv4() {
        assertThat(detector.detect("127.0.0.1")).contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsClassAIpv4() {
        assertThat(detector.detect("10.0.0.1")).contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsBroadcastIpv4() {
        assertThat(detector.detect("255.255.255.255"))
                .contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void rejectsOctetAboveRange() {
        assertThat(detector.detect("256.1.1.1")).isEmpty();
    }

    @Test
    void rejectsMissingOctet() {
        assertThat(detector.detect("192.168.1")).isEmpty();
    }

    @Test
    void rejectsExtraOctet() {
        assertThat(detector.detect("192.168.1.1.5")).isEmpty();
    }

    @Test
    void rejectsLeadingZeroOctet() {
        assertThat(detector.detect("192.168.01.1")).isEmpty();
    }

    @Test
    void rejectsMaxOctetAboveRange() {
        assertThat(detector.detect("192.168.1.999")).isEmpty();
    }

    @Test
    void rejectsAlphabeticOctets() {
        assertThat(detector.detect("abc.def.ghi.jkl")).isEmpty();
    }

    @Test
    void rejectsEmbeddedIpv4InText() {
        assertThat(detector.detect("User IP: 192.168.1.10")).isEmpty();
    }

    @Test
    void detectsFullIpv6() {
        assertThat(detector.detect("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
                .contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsCompressedIpv6() {
        assertThat(detector.detect("2001:db8::1")).contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsLoopbackIpv6() {
        assertThat(detector.detect("::1")).contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsUnspecifiedIpv6() {
        assertThat(detector.detect("::")).contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void detectsTrailingCompression() {
        assertThat(detector.detect("2001:db8::")).contains(new PiiDetection(PiiType.IP_ADDRESS));
    }

    @Test
    void rejectsInvalidHex() {
        assertThat(detector.detect("2001:db8::zz")).isEmpty();
    }

    @Test
    void rejectsTooManyGroups() {
        assertThat(detector.detect("1:2:3:4:5:6:7:8:9")).isEmpty();
    }

    @Test
    void rejectsDoubleCompression() {
        assertThat(detector.detect("2001::db8::1")).isEmpty();
    }

    @Test
    void rejectsSingleColonCompression() {
        assertThat(detector.detect("2001:db8:1")).isEmpty();
    }

    @Test
    void rejectsEmbeddedIpv6InText() {
        assertThat(detector.detect("ip 2001:db8::1 ok")).isEmpty();
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
    void rejectsSurroundingWhitespace() {
        assertThat(detector.detect(" 192.168.1.1 ")).isEmpty();
    }

    @Test
    void rejectsIpv4WithPort() {
        assertThat(detector.detect("192.168.1.1:8080")).isEmpty();
    }

    @Test
    void rejectsBracketedIpv6() {
        assertThat(detector.detect("[2001:db8::1]")).isEmpty();
    }

    @Test
    void rejectsUrlWithIp() {
        assertThat(detector.detect("http://192.168.1.1")).isEmpty();
    }

    @Test
    void resultTypeIsIpAddress() {
        assertThat(detector.detect(IPV4)).map(PiiDetection::type).contains(PiiType.IP_ADDRESS);
    }

    @Test
    void resultDoesNotContainRawValue() {
        assertThat(detector.detect(IPV4).orElseThrow().toString()).doesNotContain(IPV4);
    }

    @Test
    void registryRecognizesIpValues() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(
                new EmailDetector(),
                new PhoneDetector(),
                new CreditCardDetector(),
                new IpAddressDetector()));

        assertThat(registry.detect("192.168.1.1"))
                .containsExactly(new PiiDetection(PiiType.IP_ADDRESS));
        assertThat(registry.detect("2001:db8::1"))
                .containsExactly(new PiiDetection(PiiType.IP_ADDRESS));
    }
}
