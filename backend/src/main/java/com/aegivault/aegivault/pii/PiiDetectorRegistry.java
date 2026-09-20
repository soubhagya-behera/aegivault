package com.aegivault.aegivault.pii;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Orchestration-only registry over the available {@link PiiDetector} beans.
 *
 * <p>Runs every detector against one value and collects all matching
 * detections. Contains no detection rules itself. Results are deduplicated by
 * {@link PiiType} and sorted by {@link PiiType#name()} so ordering is
 * deterministic regardless of Spring bean discovery order. Detector failures
 * propagate so a security detector is never silently skipped.
 */
@Component
public class PiiDetectorRegistry {

    private final List<PiiDetector> detectors;

    public PiiDetectorRegistry(List<PiiDetector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    public List<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Map<PiiType, PiiDetection> byType = new EnumMap<>(PiiType.class);
        for (PiiDetector detector : detectors) {
            detector.detect(value).ifPresent(detection -> byType.putIfAbsent(detection.type(), detection));
        }
        List<PiiDetection> results = new ArrayList<>(byType.values());
        results.sort(Comparator.comparing(detection -> detection.type().name()));
        return List.copyOf(results);
    }
}
