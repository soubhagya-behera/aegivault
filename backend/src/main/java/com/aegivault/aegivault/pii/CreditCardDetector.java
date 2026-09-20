package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for payment-card numbers.
 *
 * <p>Matches the whole input value only; never scans paragraphs. Only digits
 * with single internal spaces or hyphens are allowed. After normalization the
 * digit string must be 13-19 digits long and pass the standard Luhn checksum.
 * No brand or prefix restriction is applied: the goal is PII identification,
 * not network classification. Luhn passing alone is not proof a number is a
 * real card, so this detector stays conservative and reports a finding only.
 */
@Component
public class CreditCardDetector implements PiiDetector {

    private static final Pattern ALLOWED_CHARS = Pattern.compile("[0-9](?:[0-9 \\-]*[0-9])?");

    private static final int MIN_DIGITS = 13;

    private static final int MAX_DIGITS = 19;

    private static final int MAX_RAW_LENGTH = 29;

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
        String digits = value.replace(" ", "").replace("-", "");
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            return Optional.empty();
        }
        if (!passesLuhn(digits)) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.CREDIT_CARD));
    }

    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit = digit * 2;
                if (digit > 9) {
                    digit = digit - 9;
                }
            }
            sum = sum + digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
