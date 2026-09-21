package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for password-labelled values.
 *
 * <p>Passwords cannot be recognised from arbitrary raw text, so this detector
 * never classifies a bare string as a password. It only matches an explicit
 * password label followed by a separator and a secret portion:
 * {@code password}, {@code passwd}, or {@code pwd} followed by {@code =} or
 * {@code :}.
 *
 * <p>Policy: whole-value match only; label is case-insensitive; optional
 * spaces are allowed around the separator; the secret must be 4-128
 * non-whitespace characters; input is never trimmed (surrounding whitespace
 * rejects the value); nothing is logged, stored, or returned.
 *
 * <p>Limitation: this recognises only explicitly labelled password assignments.
 * It produces false negatives for passwords stored without a label and must
 * not be treated as a general password-strength or password-discovery
 * mechanism.
 */
@Component
public class PasswordDetector implements PiiDetector {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(?:password|passwd|pwd)\\s*[:=]\\s*\\S{4,128}");

    private static final int MAX_LENGTH = 300;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (!PASSWORD_PATTERN.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.PASSWORD));
    }
}
