package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for UUID-shaped identifiers.
 *
 * <p>Matches the whole input value only; never scans text. Accepts only the
 * canonical textual form {@code 8-4-4-4-12} of case-insensitive hexadecimal
 * digits. No particular UUID version or variant is required: any
 * UUID-shaped value is a correlation identifier worth flagging. Input is
 * classified exactly as supplied; surrounding whitespace, braces,
 * parentheses, or missing/extra hyphens are rejected rather than
 * normalized.
 */
@Component
public class UuidDetector implements PiiDetector {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final int UUID_LENGTH = 36;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() != UUID_LENGTH) {
            return Optional.empty();
        }
        if (!UUID_PATTERN.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.UUID));
    }
}
