package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.pii.PiiType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable outcome of one gateway security inspection.
 *
 * <p>Carries the verdict, the safe {@link BlockReason} codes (empty exactly
 * when the verdict is {@link SecurityVerdict#ALLOW}), and the detected
 * {@link PiiType} names. It deliberately has no request-content component:
 * raw PII values, secrets, and request text can never appear here because
 * there is nowhere to put them.
 *
 * @param verdict ALLOW or BLOCK, never null
 * @param reasons safe reason codes, never null; empty if and only if the
 *        verdict is ALLOW; iteration is alphabetical by code, so repeated
 *        inspections print and compare identically
 * @param detectedPiiTypes detected PII type names, never null (possibly
 *        empty); reported regardless of whether the policy blocks on PII;
 *        iteration is alphabetical by type name
 */
public record SecurityInspectionResult(SecurityVerdict verdict, Set<BlockReason> reasons, Set<PiiType> detectedPiiTypes) {

    public SecurityInspectionResult {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(reasons, "reasons must not be null");
        Objects.requireNonNull(detectedPiiTypes, "detectedPiiTypes must not be null");
        reasons = sortedCopy(reasons, Comparator.comparing(BlockReason::name));
        detectedPiiTypes = sortedCopy(detectedPiiTypes, Comparator.comparing(PiiType::name));
        if (verdict == SecurityVerdict.ALLOW && !reasons.isEmpty()) {
            throw new IllegalArgumentException("ALLOW must carry no reasons");
        }
        if (verdict == SecurityVerdict.BLOCK && reasons.isEmpty()) {
            throw new IllegalArgumentException("BLOCK must carry at least one reason");
        }
    }

    /**
     * Copies one set into insertion-ordered, unmodifiable form, sorted so
     * every construction over equal content iterates identically.
     * ({@code Set.copyOf} alone makes no ordering promise, so sorted
     * content could still print or compare out of order.)
     */
    private static <T> Set<T> sortedCopy(Set<T> input, Comparator<T> order) {
        List<T> sorted = new ArrayList<>(input);
        sorted.sort(order);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
