package com.aegivault.aegivault.gateway.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link GatewayUsagePolicy}. Standard CRUD comes
 * from {@link JpaRepository}; every read beyond the primary key is
 * owner-scoped so the service layer can never load another owner's policy:
 * {@code findByIdAndOwnerSubject} serves single-policy lookups backed by
 * {@code idx_gateway_usage_policies_owner_subject}, and
 * {@code findByOwnerSubjectOrderByCreatedAtDescIdDesc} serves the owner's
 * listing, newest first with the id as a total-order tiebreak. There is no
 * global (cross-owner) read path by design, and no ADMIN bypass.
 *
 * <p>{@code findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc}
 * additionally filters on {@code enabled} and is the single read the
 * {@link GatewayUsagePolicyResolver} needs to pick an actor's effective
 * policy. It returns every enabled policy rather than a single row on
 * purpose: ambiguity must be detected by inspecting how many candidates
 * exist, not hidden by a query that silently takes the first one. The
 * ordering is deterministic but is a read convenience only — resolution
 * never uses it to break a tie, because a tie is an error, not a choice.
 */
public interface GatewayUsagePolicyRepository extends JpaRepository<GatewayUsagePolicy, UUID> {

    Optional<GatewayUsagePolicy> findByIdAndOwnerSubject(UUID id, String ownerSubject);

    List<GatewayUsagePolicy> findByOwnerSubjectOrderByCreatedAtDescIdDesc(String ownerSubject);

    List<GatewayUsagePolicy> findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(String ownerSubject);
}