package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for explicitly labelled custom identifiers.
 *
 * <p>Arbitrary identifier-looking strings are never classified. Only a
 * whole-value {@code label separator identifier} form is accepted, with an
 * allowlist of labels ({@code customer_id}, {@code employee_id},
 * {@code member_id}, {@code account_id}, {@code user_id}, {@code custom_id};
 * hyphens accepted as label separators), a {@code =} or {@code :} separator,
 * and a 3-64 character alphanumeric (plus {@code _}/{@code -}) identifier.
 * Label matching is case-insensitive; surrounding whitespace rejects the
 * value rather than being trimmed.
 *
 * <p>Unlabelled UUIDs, emails, phone numbers, order numbers, and bare IDs are
 * rejected. Input is classified exactly as supplied; nothing is logged,
 * stored, or returned.
 */
@Component
public class CustomIdentifierDetector implements PiiDetector {

    private static final Pattern LABELLED_IDENTIFIER = Pattern.compile(
            "(?i)(?:customer[_\\-]id|employee[_\\-]id|member[_\\-]id|"
                    + "account[_\\-]id|user[_\\-]id|custom[_\\-]id)\\s*[:=]\\s*[A-Za-z0-9_\\-]{3,64}");

    private static final int MAX_LENGTH = 200;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (!LABELLED_IDENTIFIER.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.CUSTOM_IDENTIFIER));
    }
}
