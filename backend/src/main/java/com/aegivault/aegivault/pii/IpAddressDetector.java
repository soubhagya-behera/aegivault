package com.aegivault.aegivault.pii;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for IP addresses (IPv4 and IPv6).
 *
 * <p>Matches the whole input value only; never scans text. Validation is
 * purely local string parsing: no DNS lookups, no network I/O. Surrounding
 * whitespace is rejected rather than trimmed, and ports, brackets, schemes,
 * or CIDR suffixes are not accepted. IPv4 requires strict dotted-decimal
 * (no leading-zero octets). IPv6 requires 1-4 hex digits per group with at
 * most one {@code ::} compression; dotted (IPv4-mapped/embedded) forms are
 * rejected as out of scope for this version.
 */
@Component
public class IpAddressDetector implements PiiDetector {

    private static final int MAX_LENGTH = 45;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        boolean hasColon = value.indexOf(':') >= 0;
        boolean hasDot = value.indexOf('.') >= 0;
        boolean valid;
        if (hasColon && hasDot) {
            valid = false;
        } else if (hasColon) {
            valid = isIpv6(value);
        } else if (hasDot) {
            valid = isIpv4(value);
        } else {
            valid = false;
        }
        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.IP_ADDRESS));
    }

    private boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.length() < 1 || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            int octet = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                octet = octet * 10 + (c - '0');
            }
            if (octet > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isIpv6(String value) {
        int compression = value.indexOf("::");
        if (compression < 0) {
            String[] groups = value.split(":", -1);
            return groups.length == 8 && allHexGroups(groups);
        }
        if (compression != value.lastIndexOf("::")) {
            return false;
        }
        String[] head = splitGroups(value.substring(0, compression));
        String[] tail = splitGroups(value.substring(compression + 2));
        return allHexGroups(head) && allHexGroups(tail) && head.length + tail.length <= 7;
    }

    private String[] splitGroups(String part) {
        return part.isEmpty() ? new String[0] : part.split(":", -1);
    }

    private boolean allHexGroups(String[] groups) {
        for (String group : groups) {
            if (group.length() < 1 || group.length() > 4) {
                return false;
            }
            for (int i = 0; i < group.length(); i++) {
                char c = group.charAt(i);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!hex) {
                    return false;
                }
            }
        }
        return true;
    }
}
