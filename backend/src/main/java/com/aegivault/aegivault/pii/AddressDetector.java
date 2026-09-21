package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative structural heuristic for address-like values.
 *
 * <p>This is not a global address parser and performs no geocoding, DNS, or
 * network I/O. A value is accepted only when it combines at least two of
 * three signal groups: a leading house/building number, a street keyword
 * (street, avenue, road, lane, boulevard, drive, court, circle, park, nagar,
 * marg, layout), and a postal/PIN-code-like numeric component (5-6 digits).
 * Values with {@code @}, {@code ://}, a UUID shape, or an IPv4 shape are
 * rejected, as are values without any street keyword.
 *
 * <p>Version 1 is heuristic and geography-limited; it favours false negatives
 * over classifying every string containing a number as an address. Input is
 * classified exactly as supplied; raw values are never logged, stored, or
 * returned.
 */
@Component
public class AddressDetector implements PiiDetector {

    private static final Pattern STREET_KEYWORD = Pattern.compile(
            "(?i).*\\b(street|st\\.?|avenue|ave\\.?|road|rd\\.?|lane|ln\\.?|"
                    + "boulevard|blvd\\.?|drive|dr\\.?|court|ct\\.?|circle|cir\\.?|"
                    + "park|nagar|marg|layout)\\b.*");

    private static final Pattern POSTAL_CODE = Pattern.compile(".*\\b\\d{5,6}\\b.*");

    private static final Pattern HOUSE_NUMBER = Pattern.compile("^\\d{1,6}[A-Za-z]?\\s+.*");

    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern IPV4_SHAPE =
            Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    private static final int MAX_LENGTH = 300;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (value.contains("@") || value.contains("://")) {
            return Optional.empty();
        }
        if (UUID_SHAPE.matcher(value).find() || IPV4_SHAPE.matcher(value).find()) {
            return Optional.empty();
        }
        if (!STREET_KEYWORD.matcher(value).matches()) {
            return Optional.empty();
        }
        boolean hasHouseNumber = HOUSE_NUMBER.matcher(value).matches();
        boolean hasPostalCode = POSTAL_CODE.matcher(value).matches();
        if (!hasHouseNumber && !hasPostalCode) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.ADDRESS));
    }
}
