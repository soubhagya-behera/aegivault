package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.pii.PiiType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One stored PII detection of a {@link StoredProfileColumn}: which
 * {@link PiiType} was observed, in how many analyzed values, and at what
 * observed rate.
 *
 * <p>Embeddable element of the normalized
 * {@code dataset_profile_detections} table (V8): one row per detected type
 * per column. Carries the profiler-reported count and rate verbatim —
 * never raw values, never samples.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class StoredProfileDetection {

    @Enumerated(EnumType.STRING)
    @Column(name = "pii_type", nullable = false, length = 64)
    private PiiType piiType;

    @Column(name = "detection_count", nullable = false)
    private int detectionCount;

    @Column(name = "detection_rate", nullable = false)
    private double detectionRate;

    /**
     * @param piiType detected PII type, never null
     * @param detectionCount how many analyzed values contained the type,
     *        never negative
     * @param detectionRate observed fraction of analyzed non-blank values
     *        containing the type, between 0.0 and 1.0 inclusive
     */
    public StoredProfileDetection(PiiType piiType, int detectionCount, double detectionRate) {
        this.piiType = Objects.requireNonNull(piiType, "piiType must not be null");
        if (detectionCount < 0) {
            throw new IllegalArgumentException("detectionCount must not be negative");
        }
        if (Double.isNaN(detectionRate) || detectionRate < 0.0 || detectionRate > 1.0) {
            throw new IllegalArgumentException("detectionRate must be between 0.0 and 1.0");
        }
        this.detectionCount = detectionCount;
        this.detectionRate = detectionRate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StoredProfileDetection detection)) {
            return false;
        }
        return detectionCount == detection.detectionCount
                && Double.compare(detectionRate, detection.detectionRate) == 0
                && piiType == detection.piiType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(piiType, detectionCount, detectionRate);
    }
}
