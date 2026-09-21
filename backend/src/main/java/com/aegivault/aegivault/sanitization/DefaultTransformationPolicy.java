package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;
import java.util.List;

/**
 * The explicit default sanitization policy Aegivault applies when no
 * caller-supplied {@link TransformationPlan} is provided.
 *
 * <p>This is an <em>application policy</em>, not a property of
 * {@link PiiType} and not a claim of universal safety or compliance. It is a
 * deliberate starting point that transforms every detected type instead of
 * silently keeping one:
 *
 * <pre>
 * EMAIL             -&gt; SYNTHETIC_EMAIL   (deterministic example.invalid address)
 * PHONE             -&gt; SYNTHETIC_PHONE   (deterministic value in the project's phone format)
 * PERSON_NAME       -&gt; REDACT            (free text; masking would still leak a tail)
 * ADDRESS           -&gt; REDACT            (free text; masking would still leak a tail)
 * CREDIT_CARD       -&gt; MASK              (keeps an explicit 4-character tail, like card receipts)
 * IP_ADDRESS        -&gt; HASH_SHA256       (stable pseudonym for correlation)
 * UUID              -&gt; HASH_SHA256       (stable pseudonym for correlation)
 * API_KEY           -&gt; REDACT            (no part of a credential may survive)
 * PASSWORD          -&gt; HASH_SHA256       (value must never reappear in clear text)
 * JWT               -&gt; REDACT            (whole token is a bearer credential)
 * CUSTOM_IDENTIFIER -&gt; HASH_SHA256       (stable pseudonym for correlation)
 * </pre>
 *
 * <p>Every choice is a policy decision that a deployment may override by
 * supplying its own {@link TransformationPlan}; the engine itself has no
 * built-in preference and never reads this class.
 *
 * <p>Limitations: hashing is deterministic and unsalted, so it preserves
 * relationships and is vulnerable to guessing for low-entropy values; MASK
 * keeps the last four characters on purpose; the synthetic strategies produce
 * synthetic data with no reserved-range or uniqueness guarantee.
 */
public final class DefaultTransformationPolicy {

    private DefaultTransformationPolicy() {
    }

    /**
     * Builds the default policy plan.
     *
     * @return an immutable plan covering all eleven {@link PiiType} values
     */
    public static TransformationPlan plan() {
        return TransformationPlan.of(List.of(
                rule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL),
                rule(PiiType.PHONE, TransformationStrategy.SYNTHETIC_PHONE),
                rule(PiiType.PERSON_NAME, TransformationStrategy.REDACT),
                rule(PiiType.ADDRESS, TransformationStrategy.REDACT),
                rule(PiiType.CREDIT_CARD, TransformationStrategy.MASK),
                rule(PiiType.IP_ADDRESS, TransformationStrategy.HASH_SHA256),
                rule(PiiType.UUID, TransformationStrategy.HASH_SHA256),
                rule(PiiType.API_KEY, TransformationStrategy.REDACT),
                rule(PiiType.PASSWORD, TransformationStrategy.HASH_SHA256),
                rule(PiiType.JWT, TransformationStrategy.REDACT),
                rule(PiiType.CUSTOM_IDENTIFIER, TransformationStrategy.HASH_SHA256)));
    }

    private static TransformationRule rule(PiiType piiType, TransformationStrategy strategy) {
        return new TransformationRule(piiType, strategy);
    }
}
