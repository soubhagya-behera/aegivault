package com.aegivault.aegivault.pii.profile;

import com.aegivault.aegivault.pii.PiiDetection;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Profiles one dataset column over a deterministic bounded sample.
 *
 * <p>Takes the first {@code maxSampleSize} supplied values in order (default
 * 100), runs each through the existing {@link PiiDetectorRegistry}, and
 * aggregates per-type detection counts plus observed detection rates. The
 * rate denominator is the number of analyzed non-blank values (nulls and
 * blanks are counted in the sample but excluded from the denominator, so an
 * empty column yields zero rates instead of a division by zero). Results
 * record both the supplied and analyzed counts so callers never mistake a
 * bounded sample for a full-dataset scan.
 *
 * <p>Raw values are never stored, logged, or included in results or
 * exceptions.
 */
@Component
public class PiiColumnProfiler {

    static final int DEFAULT_MAX_SAMPLE_SIZE = 100;

    private final PiiDetectorRegistry registry;

    private final int maxSampleSize;

    @Autowired
    public PiiColumnProfiler(PiiDetectorRegistry registry) {
        this(registry, DEFAULT_MAX_SAMPLE_SIZE);
    }

    public PiiColumnProfiler(PiiDetectorRegistry registry, int maxSampleSize) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        if (maxSampleSize < 1) {
            throw new IllegalArgumentException("maxSampleSize must be at least 1");
        }
        this.maxSampleSize = maxSampleSize;
    }

    /**
     * Effective maximum number of supplied values analyzed per column.
     *
     * @return the bounded sample limit for {@link #profile(ColumnInput)}
     */
    public int maxSampleSize() {
        return maxSampleSize;
    }

    public ColumnProfile profile(ColumnInput input) {
        Objects.requireNonNull(input, "input must not be null");
        List<String> supplied = input.values();
        int suppliedCount = supplied.size();
        List<String> sample = new ArrayList<>(supplied.subList(0, Math.min(suppliedCount, maxSampleSize)));
        int analyzedCount = sample.size();

        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        int analyzableCount = 0;
        for (String value : sample) {
            if (value == null || value.isBlank()) {
                continue;
            }
            analyzableCount++;
            List<PiiDetection> detections = registry.detect(value);
            for (PiiDetection detection : detections) {
                counts.merge(detection.type(), 1, Integer::sum);
            }
        }

        Map<PiiType, Double> rates = new EnumMap<>(PiiType.class);
        for (Map.Entry<PiiType, Integer> entry : counts.entrySet()) {
            double rate = analyzableCount == 0 ? 0.0 : (double) entry.getValue() / analyzableCount;
            rates.put(entry.getKey(), rate);
        }

        Set<PiiType> detectedTypes = new TreeSet<>(Comparator.comparing(PiiType::name));
        detectedTypes.addAll(counts.keySet());

        return new ColumnProfile(
                input.columnName(),
                suppliedCount,
                analyzedCount,
                analyzableCount,
                Map.copyOf(counts),
                Map.copyOf(rates),
                Set.copyOf(detectedTypes));
    }
}
