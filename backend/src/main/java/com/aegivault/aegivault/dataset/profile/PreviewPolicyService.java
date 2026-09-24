package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.policy.PolicyResponse;
import com.aegivault.aegivault.sanitization.policy.SanitizationPolicyService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a persistent sanitization policy from a persisted dataset
 * profile and the existing default transformation plan.
 *
 * <p>Flow, and nothing else: {@link DatasetProfileService#getProfile}
 * (owner-scoped read of the already-persisted profile — profiling is never
 * re-run, the stored CSV is never opened, raw values are never inspected) →
 * collect the detected {@code PiiType} values only (undetected types are
 * never added) → resolve each through {@link DefaultTransformationPolicy}
 * → persist through {@link SanitizationPolicyService#create}, which owns
 * all label/rule validation and returns the standard {@link PolicyResponse}.
 * No creation or validation logic is duplicated here: labels are validated
 * by the request annotations first and re-validated by the aggregate, and
 * the at-least-one-rule and one-strategy-per-type invariants hold because
 * the aggregate enforces them for every caller.
 *
 * <p>Atomicity is by construction: every rule is resolved before the single
 * {@code create} call, so a failure (no detections, or a detected type with
 * no default strategy) happens before any persistence and no partial policy
 * can remain. An empty detection set fails with
 * {@link EmptyTransformationPreviewException} instead of creating an empty
 * policy; an unmapped type fails with {@link MissingTransformationException}
 * instead of an invented strategy.
 *
 * <p>This creates a policy and nothing else: no run is created, no
 * sanitization executes, the dataset, profile, and uploaded CSV are
 * untouched, and no audit entry is appended (policy creation does not audit
 * anywhere else either — no new audit design here).
 */
@Service
@RequiredArgsConstructor
public class PreviewPolicyService {

    private final DatasetProfileService profiles;

    private final SanitizationPolicyService policies;

    /**
     * Creates a persistent policy owned by the caller from one owned
     * dataset's persisted profile.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only);
     *        must own the dataset
     * @param datasetId dataset whose persisted profile supplies the rules,
     *        never null
     * @param name human label, never blank, at most 255 characters
     * @param version version label, never blank, at most 255 characters
     * @param description optional free text, null or at most 1024 characters
     * @return the persisted policy view, exactly as {@code POST /api/policies}
     * @throws DatasetNotFoundException when the dataset is missing, belongs
     *         to another owner, or has no persisted profile yet (identical
     *         either way)
     * @throws EmptyTransformationPreviewException when the persisted profile
     *         contains no detected PII types (nothing is persisted)
     * @throws MissingTransformationException when a detected type has no
     *         default strategy (fail-closed before persistence; never an
     *         invented strategy)
     */
    @Transactional
    public PolicyResponse createPolicyFromPreview(
            String ownerSubject, UUID datasetId, String name, String version, String description) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        DatasetProfileResponse profile = profiles.getProfile(owner, datasetId);
        List<TransformationRule> rules = defaultRulesFor(profile);
        return policies.create(owner, name, version, description, rules);
    }

    /**
     * Resolves one default rule per detected PII type, in first-appearance
     * order (profile column order, alphabetical within a column). Only
     * detected types appear; everything resolves before the caller persists.
     */
    private static List<TransformationRule> defaultRulesFor(DatasetProfileResponse profile) {
        TransformationPlan defaults = DefaultTransformationPolicy.plan();
        Set<PiiType> detected = new LinkedHashSet<>();
        for (DatasetProfileResponse.ColumnProfileResponse column : profile.columns()) {
            List<PiiType> ordered = new ArrayList<>(column.detectedTypes());
            ordered.sort(Comparator.comparing(PiiType::name));
            detected.addAll(ordered);
        }
        if (detected.isEmpty()) {
            throw new EmptyTransformationPreviewException();
        }
        return detected.stream()
                .map(type -> new TransformationRule(
                        type,
                        defaults.strategyFor(type)
                                .orElseThrow(() -> new MissingTransformationException(type))))
                .toList();
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
