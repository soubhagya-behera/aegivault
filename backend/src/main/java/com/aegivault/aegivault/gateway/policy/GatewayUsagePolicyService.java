package com.aegivault.aegivault.gateway.policy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped persistence for gateway usage policy definitions. The
 * invariant is {@code JWT sub -> ownerSubject -> owner-scoped repository
 * query}: USER and ADMIN behave identically, there is no cross-user access,
 * and missing ids are indistinguishable from other owners' ids.
 *
 * <p>This service stores and reads definitions and nothing else. It does
 * not enforce, evaluate, count, or meter anything: no gateway traffic
 * consults these rows, no usage counter is derived from them, and the rate
 * limiter, completion service, provider selection, usage recording, and
 * audit ledger are all untouched. Every label bound, the positive-limit
 * rule, and the at-least-one-limit rule are enforced by
 * {@link GatewayUsagePolicy} itself, so they hold for any caller.
 */
@Service
@RequiredArgsConstructor
public class GatewayUsagePolicyService {

    private final GatewayUsagePolicyRepository policies;

    /**
     * Persists a new policy definition for the calling owner, in one
     * transaction.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only)
     * @param name label, never blank, at most 255 characters
     * @param description optional free text, null or at most 1024 characters
     * @param requestsPerMinute declared requests per minute, null or positive
     * @param requestsPerDay declared requests per day, null or positive
     * @param tokensPerDay declared tokens per day, null or positive
     * @param enabled whether the definition is switched on
     * @return the persisted view
     * @throws IllegalArgumentException when the aggregate rejects its input
     */
    @Transactional
    public GatewayUsagePolicyResponse create(
            String ownerSubject,
            String name,
            String description,
            Long requestsPerMinute,
            Long requestsPerDay,
            Long tokensPerDay,
            boolean enabled) {
        String owner = requireOwner(ownerSubject);
        GatewayUsagePolicy policy = new GatewayUsagePolicy(
                owner, name, description, requestsPerMinute, requestsPerDay, tokensPerDay, enabled);
        return GatewayUsagePolicyResponse.from(policies.saveAndFlush(policy));
    }

    /**
     * Lists the caller's policies, newest first (creation instant, then id
     * as a total-order tiebreak).
     */
    @Transactional(readOnly = true)
    public List<GatewayUsagePolicyResponse> list(String ownerSubject) {
        String owner = requireOwner(ownerSubject);
        return policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner).stream()
                .map(GatewayUsagePolicyResponse::from)
                .toList();
    }

    /**
     * Reads one caller's policy.
     *
     * @throws GatewayUsagePolicyNotFoundException when the policy is missing
     *         or belongs to another owner (identical either way)
     */
    @Transactional(readOnly = true)
    public GatewayUsagePolicyResponse get(String ownerSubject, UUID policyId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(policyId, "policyId must not be null");
        return GatewayUsagePolicyResponse.from(owned(policyId, owner));
    }

    /**
     * Replaces one caller's policy — label, limits, and enabled state — in
     * place, in one transaction. The policy id and owner never change and no
     * new row is created.
     *
     * @throws GatewayUsagePolicyNotFoundException when the policy is missing
     *         or belongs to another owner (identical either way)
     * @throws IllegalArgumentException when the aggregate rejects its input;
     *         the transaction rolls back, so the stored policy is untouched
     */
    @Transactional
    public GatewayUsagePolicyResponse update(
            String ownerSubject,
            UUID policyId,
            String name,
            String description,
            Long requestsPerMinute,
            Long requestsPerDay,
            Long tokensPerDay,
            boolean enabled) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(policyId, "policyId must not be null");
        GatewayUsagePolicy policy = owned(policyId, owner);
        policy.update(name, description, requestsPerMinute, requestsPerDay, tokensPerDay, enabled);
        return GatewayUsagePolicyResponse.from(policies.saveAndFlush(policy));
    }

    /**
     * Deletes one caller's policy in one transaction. Nothing else references
     * a policy, and no gateway behavior changes either way.
     *
     * @throws GatewayUsagePolicyNotFoundException when the policy is missing
     *         or belongs to another owner (identical either way)
     */
    @Transactional
    public void delete(String ownerSubject, UUID policyId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(policyId, "policyId must not be null");
        policies.delete(owned(policyId, owner));
    }

    private GatewayUsagePolicy owned(UUID policyId, String owner) {
        return policies
                .findByIdAndOwnerSubject(policyId, owner)
                .orElseThrow(GatewayUsagePolicyNotFoundException::new);
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}