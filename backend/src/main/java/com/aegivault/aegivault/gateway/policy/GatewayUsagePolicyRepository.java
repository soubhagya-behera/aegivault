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
 */
public interface GatewayUsagePolicyRepository extends JpaRepository<GatewayUsagePolicy, UUID> {

    Optional<GatewayUsagePolicy> findByIdAndOwnerSubject(UUID id, String ownerSubject);

    List<GatewayUsagePolicy> findByOwnerSubjectOrderByCreatedAtDescIdDesc(String ownerSubject);
}