package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.springframework.stereotype.Component;

/**
 * SYNTHETIC_PHONE: deterministic synthetic number inside the phone format this
 * project accepts.
 *
 * <p>The project's phone detector accepts ten digits whose first digit is
 * {@code 6}-{@code 9} (optionally with a trunk {@code 0} or country {@code 91}
 * prefix). Digit one is derived from the first digest byte mapped into that
 * accepted range, and the remaining nine digits come from the following digest
 * bytes, so the output always satisfies the accepted format while being derived
 * from the input value alone. The output therefore contains no part of the
 * original number and is stable for the same input.
 *
 * <p>Explicit limitation: this makes no reserved-range claim. There is no
 * globally reserved mobile range this project can rely on, so a generated value
 * is synthetic data that must never be treated as a real, dialable contact.
 * Synthetic generation deliberately avoids {@code Math.random()}, counters, and
 * external fake-data services, because predictability and repeatability matter
 * more here than novelty.
 */
@Component
public class SyntheticPhoneTransformation implements ValueTransformation {

    /** Digit count of the accepted project phone format. */
    public static final int DIGIT_COUNT = 10;

    private static final int FIRST_DIGIT_MIN = 6;

    private static final int FIRST_DIGIT_SPAN = 4;

    private static final int DECIMAL_SPAN = 10;

    @Override
    public TransformationStrategy strategy() {
        return TransformationStrategy.SYNTHETIC_PHONE;
    }

    @Override
    public String apply(String value) {
        byte[] digest = Sha256Digest.bytes(value);
        StringBuilder digits = new StringBuilder(DIGIT_COUNT);
        digits.append(FIRST_DIGIT_MIN + Math.floorMod(digest[0], FIRST_DIGIT_SPAN));
        for (int index = 1; index < DIGIT_COUNT; index++) {
            digits.append(Math.floorMod(digest[index], DECIMAL_SPAN));
        }
        return digits.toString();
    }
}
