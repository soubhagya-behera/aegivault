package com.aegivault.aegivault.pii;

import java.util.Optional;

/**
 * Contract for future PII detectors.
 *
 * <p>A detector inspects one raw value and reports the {@link PiiDetection}
 * when its PII type is present, or {@link Optional#empty()} when it is not
 * (including null or blank input). Implementations must never return null
 * and must never embed the raw sensitive value in the result.
 */
public interface PiiDetector {

    Optional<PiiDetection> detect(String value);
}
