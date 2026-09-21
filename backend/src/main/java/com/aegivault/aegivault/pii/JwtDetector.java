package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, structural detector for JWT compact-serialization values.
 *
 * <p>Matches only values with exactly three dot-separated Base64URL segments
 * (header.payload.signature). Each segment must be non-empty, contain only
 * Base64URL characters, and meet a minimum length so arbitrary
 * {@code a.b.c} strings are not classified as tokens.
 *
 * <p>No decoding, signature verification, expiry checks, issuer contact, or
 * network I/O is performed. Structural recognition is not proof of
 * authenticity. Input is classified exactly as supplied; nothing is trimmed,
 * logged, stored, or returned.
 */
@Component
public class JwtDetector implements PiiDetector {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private static final int MIN_SEGMENT_LENGTH = 8;

    private static final int MAX_LENGTH = 8192;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        String[] segments = value.split("\\.", -1);
        if (segments.length != 3) {
            return Optional.empty();
        }
        for (String segment : segments) {
            if (segment.length() < MIN_SEGMENT_LENGTH) {
                return Optional.empty();
            }
            if (!SEGMENT.matcher(segment).matches()) {
                return Optional.empty();
            }
        }
        return Optional.of(new PiiDetection(PiiType.JWT));
    }
}
