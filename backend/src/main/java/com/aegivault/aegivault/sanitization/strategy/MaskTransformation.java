package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MASK: generic tail-preserving mask.
 *
 * <p>Every character except an explicit visible suffix is replaced with
 * {@code *}; the suffix is copied verbatim. With the default suffix of four
 * characters, a sixteen-digit card-like value
 * {@code 4111111111111111} becomes {@code ************1111}, which matches the
 * customary last-four display used on receipts. A value that is no longer than
 * the visible suffix is masked entirely, so no value is ever returned
 * unchanged except through
 * {@link com.aegivault.aegivault.sanitization.TransformationStrategy#KEEP}.
 *
 * <p>This is intentionally a <em>generic</em> mask and not a credit-card
 * algorithm: it does not validate, normalise, or understand the structure of
 * any particular identifier, it preserves the character count, and it keeps
 * whatever characters happen to sit in the tail. Policies that need a
 * type-specific rule (for example stricter handling of credentials) should map
 * that type to a different strategy rather than assuming MASK is correct
 * everywhere. MASK therefore leaks the value length and a small tail; it is not
 * anonymization.
 */
@Component
public class MaskTransformation implements ValueTransformation {

    /** Number of trailing characters kept verbatim by default. */
    public static final int DEFAULT_VISIBLE_SUFFIX = 4;

    private static final String MASK_CHARACTER = "*";

    private final int visibleSuffix;

    /** Creates the mask with {@value #DEFAULT_VISIBLE_SUFFIX} visible trailing characters. */
    @Autowired
    public MaskTransformation() {
        this(DEFAULT_VISIBLE_SUFFIX);
    }

    /**
     * @param visibleSuffix trailing characters to keep verbatim, zero or more
     */
    public MaskTransformation(int visibleSuffix) {
        if (visibleSuffix < 0) {
            throw new IllegalArgumentException("visibleSuffix must not be negative");
        }
        this.visibleSuffix = visibleSuffix;
    }

    @Override
    public TransformationStrategy strategy() {
        return TransformationStrategy.MASK;
    }

    @Override
    public String apply(String value) {
        int length = value.length();
        if (length <= visibleSuffix) {
            return MASK_CHARACTER.repeat(length);
        }
        return MASK_CHARACTER.repeat(length - visibleSuffix) + value.substring(length - visibleSuffix);
    }
}
