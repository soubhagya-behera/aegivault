package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.springframework.stereotype.Component;

/**
 * SYNTHETIC_EMAIL: deterministic synthetic address of the form
 * {@code user-<token>@example.invalid}.
 *
 * <p>The token is the first {@value #TOKEN_LENGTH} hexadecimal characters of the
 * SHA-256 digest of the input value, so the same input always produces the same
 * address and distinct inputs produce distinct addresses with overwhelming
 * probability. No part of the original address is preserved: the local part is
 * discarded entirely, and the domain is fixed.
 *
 * <p>{@code example.invalid} is used on purpose — {@code .invalid} is reserved
 * by RFC 2606 for names that must never resolve, so the output cannot collide
 * with a real mailbox, is not routable, and no external service is contacted.
 * The result still satisfies the project's email detector, so a downstream
 * re-scan of sanitized output continues to recognise the column as email data.
 *
 * <p>The transformation is deterministic over the exact input string; it does
 * not normalise case or whitespace, so {@code alice@example.com} and
 * {@code ALICE@EXAMPLE.COM} produce different tokens. Normalisation would be a
 * product decision and is not implemented.
 */
@Component
public class SyntheticEmailTransformation implements ValueTransformation {

    /** Number of digest characters used as the stable local-part token. */
    public static final int TOKEN_LENGTH = 12;

    /** Reserved, never-resolvable synthetic domain used for every generated address. */
    public static final String SYNTHETIC_DOMAIN = "example.invalid";

    private static final String LOCAL_PART_PREFIX = "user-";

    @Override
    public TransformationStrategy strategy() {
        return TransformationStrategy.SYNTHETIC_EMAIL;
    }

    @Override
    public String apply(String value) {
        String token = Sha256Digest.hex(value).substring(0, TOKEN_LENGTH);
        return LOCAL_PART_PREFIX + token + "@" + SYNTHETIC_DOMAIN;
    }
}
