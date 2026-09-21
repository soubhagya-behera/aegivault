package com.aegivault.aegivault.sanitization;

/**
 * Closed set of supported transformation strategies.
 *
 * <p>A strategy is the <em>identity</em> of a transformation, not its
 * implementation: behaviour lives in
 * {@code com.aegivault.aegivault.sanitization.strategy} implementations that
 * are resolved through the transformation registry. Detection and
 * transformation remain separate concerns — {@code PiiType} says what a value
 * was detected as, a {@link TransformationPlan} says what should happen to it,
 * and this enum is the vocabulary in between.
 *
 * <p>Every strategy is deterministic and pure: the same input value always
 * produces the same output value, with no randomness, no counters, no
 * contextual state, and no external service. That is what makes repeated values
 * (for example the same email address appearing twice in one column) map to
 * identical sanitized values without any mapping store.
 *
 * <p>No strategy claims irreversible anonymization. {@link #HASH_SHA256} is a
 * deterministic, unsalted pseudonymization-like transformation rather than a
 * guarantee of anonymity, {@link #MASK} deliberately keeps a small tail of the
 * original value, {@link #KEEP} returns the value unchanged, and the synthetic
 * strategies derive a new value from the input without claiming uniqueness or
 * unlinkability.
 */
public enum TransformationStrategy {

    /** Returns the value unchanged; only for explicitly approved, non-sensitive data. */
    KEEP,

    /** Replaces the whole value with a fixed placeholder, retaining no characters of the original. */
    REDACT,

    /** Generic tail-preserving mask: replaces everything except a small explicit suffix with {@code *}. */
    MASK,

    /** Deterministic synthetic address of the form {@code user-<token>@example.invalid}. */
    SYNTHETIC_EMAIL,

    /** Deterministic synthetic value in the project's accepted phone format. */
    SYNTHETIC_PHONE,

    /** Deterministic unsalted SHA-256 of the UTF-8 value, lowercase hexadecimal, 64 characters. */
    HASH_SHA256
}
