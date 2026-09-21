package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative heuristic detector for person-name-like values.
 *
 * <p>This is heuristic detection, not authoritative identity recognition. No
 * NLP library is used. A value is accepted only when it consists of one to
 * three alphabetic name tokens, each 2-30 characters, starting with an
 * uppercase letter followed by lowercase letters, with optional internal
 * hyphen or apostrophe joining capitalized parts (for example
 * {@code Anne-Marie} or {@code O'Brien}).
 *
 * <p>Single generic role words such as {@code Administrator},
 * {@code Customer}, or {@code Developer} are rejected via an explicit
 * blocklist, and values containing digits, symbols, email/URL markers, or
 * sentence structure (periods, commas beyond the name shape) are rejected.
 * The policy prefers false negatives over false positives. Input is
 * classified exactly as supplied; nothing is trimmed, logged, stored, or
 * returned.
 */
@Component
public class PersonNameDetector implements PiiDetector {

    private static final Pattern TOKEN = Pattern.compile(
            "[A-Z](?:[a-z]{1,29}(?:[-'][A-Z][a-z]{1,29})?|[-'][A-Z][a-z]{1,29})");

    private static final Set<String> BLOCKLIST = Set.of(
            "administrator",
            "customer",
            "developer",
            "manager",
            "operator",
            "support",
            "service",
            "account",
            "unknown",
            "anonymous",
            "test",
            "user",
            "admin",
            "system",
            "guest");

    private static final int MAX_LENGTH = 100;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (value.contains("@") || value.contains("://") || value.contains("_")
                || value.contains("/") || value.contains("\\")) {
            return Optional.empty();
        }
        if (value.chars().anyMatch(Character::isDigit)) {
            return Optional.empty();
        }
        String[] tokens = value.split(" ", -1);
        if (tokens.length < 1 || tokens.length > 3) {
            return Optional.empty();
        }
        for (String token : tokens) {
            if (token.isEmpty() || !TOKEN.matcher(token).matches()) {
                return Optional.empty();
            }
        }
        if (tokens.length == 1 && BLOCKLIST.contains(tokens[0].toLowerCase())) {
            return Optional.empty();
        }
        return Optional.of(new PiiDetection(PiiType.PERSON_NAME));
    }
}
