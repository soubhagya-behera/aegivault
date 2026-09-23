package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.sanitization.TransformationRule;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped policy persistence. The invariant is
 * {@code JWT sub -> ownerSubject -> owner-scoped repository query}: USER and
 * ADMIN behave identically, no cross-user access exists here, and missing
 * ids are indistinguishable from other owners' ids.
 *
 * <p>The service persists and reads the {@link SanitizationPolicy} aggregate
 * only. It never invents rules, never resolves a run's policy — run creation
 * still carries its own inline rules — and never touches CSV reading,
 * detection, or transformation code: those boundaries stay where they are.
 * Every label bound, the at-least-one-rule rule, and the one-strategy-per-PII-type
 * invariant are enforced by the aggregate itself, so they hold for any
 * caller, not just this one.
 */
@Service
@RequiredArgsConstructor
public class SanitizationPolicyService {

    private final SanitizationPolicyRepository policies;

    /**
     * Persists a new policy for the calling owner, including its rules, in
     * one transaction.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only)
     * @param name label, never blank, at most 255 characters
     * @param version version label, never blank, at most 255 characters
     * @param description optional free text, null or at most 1024 characters
     * @param rules explicit rules, never null, never empty, no duplicates
     * @return the persisted view
     * @throws IllegalArgumentException when the aggregate rejects its input
     */
    @Transactional
    public PolicyResponse create(
            String ownerSubject,
            String name,
            String version,
            String description,
            List<TransformationRule> rules) {
        String owner = requireOwner(ownerSubject);
        SanitizationPolicy policy = new SanitizationPolicy(owner, name, version, description, rules);
        return PolicyResponse.from(policies.saveAndFlush(policy));
    }

    /**
     * Lists the caller's policies, newest first (creation instant, then id
     * as a total-order tiebreak).
     */
    @Transactional(readOnly = true)
    public List<PolicyResponse> list(String ownerSubject) {
        String owner = requireOwner(ownerSubject);
        return policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner).stream()
                .map(PolicyResponse::from)
                .toList();
    }

    /**
     * Reads one caller's policy with its rules.
     *
     * @throws PolicyNotFoundException when the policy is missing or belongs
     *         to another owner (identical either way)
     */
    @Transactional(readOnly = true)
    public PolicyResponse get(String ownerSubject, UUID policyId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(policyId, "policyId must not be null");
        return PolicyResponse.from(policies
                .findByIdAndOwnerSubject(policyId, owner)
                .orElseThrow(PolicyNotFoundException::new));
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
