package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for email addresses.
 *
 * <p>Matches the whole input value only; never scans paragraphs. The pattern
 * is intentionally a practical subset (not the full RFC grammar): an
 * ASCII local part, exactly one '@', dot-separated domain labels, and an
 * alphabetic top-level domain of at least two characters.
 */
@Component
public class EmailDetector implements PiiDetector {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}");

    private static final int MAX_EMAIL_LENGTH = 254;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_EMAIL_LENGTH) {
            return Optional.empty();
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.EMAIL));
    }
}
