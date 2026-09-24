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
 * Immutable outcome of one provider-response security inspection.
 *
 * <p>Deliberately distinct from {@link SecurityInspectionResult}: the
 * request inspection decides whether a prompt may reach the provider,
 * while this result decides whether an already-produced provider
 * response may reach the client. The two decisions share the same
 * strict policy vocabulary (PII blocks, secrets block) but never share
 * an instance — request and response verdicts stay separate values.
 *
 * <p>Carries the verdict, safe {@link BlockReason} codes (empty exactly
 * when ALLOW), and detected {@link PiiType} names. It has no
 * response-content component: raw PII values, secrets, and provider
 * text can never appear here because there is nowhere to put them.
 *
 * @param verdict ALLOW or BLOCK, never null
 * @param reasons safe reason codes, never null; empty if and only if the
 *        verdict is ALLOW; iteration is alphabetical by code
 * @param detectedPiiTypes detected PII type names, never null (possibly
 *        empty); reported regardless of whether the policy blocks on PII;
 *        iteration is alphabetical by type name
 */
public record ProviderResponseInspectionResult(
        SecurityVerdict verdict, Set<BlockReason> reasons, Set<PiiType> detectedPiiTypes) {

    public ProviderResponseInspectionResult {
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

    private static <T> Set<T> sortedCopy(Set<T> input, Comparator<T> order) {
        List<T> sorted = new ArrayList<>(input);
        sorted.sort(order);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
