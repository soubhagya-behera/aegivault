package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.springframework.stereotype.Component;

/**
 * HASH_SHA256: deterministic unsalted SHA-256 of the UTF-8 value, lowercase
 * hexadecimal, always 64 characters.
 *
 * <p>This is a relationship-preserving pseudonymization-like transformation and
 * <em>not</em> a claim of anonymization: identical inputs always produce
 * identical outputs (that is the point — joins and duplicate detection still
 * work), which also means values remain linkable across datasets that were
 * hashed with the same configuration. Because the hash is unsalted and
 * deterministic, low-entropy values (for example a ten-digit phone number) can
 * be recovered by guessing; salting or keyed pseudonymization would change that
 * trade-off and is a deliberate later decision.
 *
 * <p>Input encoding is UTF-8, output is lowercase hex, and no salt or randomness
 * is applied. The input value never appears in the output, is never logged, is
 * never persisted, and is never included in an exception message.
 */
@Component
public class Sha256HashTransformation implements ValueTransformation {

    /** Length of a SHA-256 lowercase hexadecimal digest. */
    public static final int HASH_LENGTH = 64;

    @Override
    public TransformationStrategy strategy() {
        return TransformationStrategy.HASH_SHA256;
    }

    @Override
    public String apply(String value) {
        return Sha256Digest.hex(value);
    }
}
