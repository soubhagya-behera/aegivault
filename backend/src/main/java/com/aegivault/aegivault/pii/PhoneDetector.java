package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for phone numbers.
 *
 * <p>Matches the whole input value only; never scans paragraphs. This first
 * version focuses on common Indian/international-style values: a 10-digit
 * mobile starting with 6-9, optionally prefixed with trunk {@code 0} or
 * country code {@code 91} (with or without a leading {@code +}). Single
 * spaces or hyphens may separate digit groups.
 */
@Component
public class PhoneDetector implements PiiDetector {

    private static final Pattern ALLOWED_CHARS = Pattern.compile("\\+?[0-9](?:[0-9 \\-]*[0-9])?");

    private static final Pattern NATIONAL = Pattern.compile("[6-9]\\d{9}");

    private static final Pattern TRUNK_PREFIXED = Pattern.compile("0[6-9]\\d{9}");

    private static final Pattern COUNTRY_PREFIXED = Pattern.compile("91[6-9]\\d{9}");

    private static final int MAX_RAW_LENGTH = 20;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_RAW_LENGTH) {
            return Optional.empty();
        }
        if (!ALLOWED_CHARS.matcher(value).matches()) {
            return Optional.empty();
        }
        if (value.contains("  ")
                || value.contains("--")
                || value.contains(" -")
                || value.contains("- ")) {
            return Optional.empty();
        }
        String digits = value.replaceAll("[ \\-]", "");
        if (value.startsWith("+")) {
            if (!digits.startsWith("+91")) {
                return Optional.empty();
            }
            if (!COUNTRY_PREFIXED.matcher(digits.substring(1)).matches()) {
                return Optional.empty();
            }
        } else if (!NATIONAL.matcher(digits).matches()
                && !TRUNK_PREFIXED.matcher(digits).matches()
                && !COUNTRY_PREFIXED.matcher(digits).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.PHONE));
    }
}
