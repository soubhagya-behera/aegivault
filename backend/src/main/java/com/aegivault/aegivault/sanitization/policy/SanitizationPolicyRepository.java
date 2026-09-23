package com.aegivault.aegivault.sanitization.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link SanitizationPolicy}. Standard CRUD comes
 * from {@link JpaRepository}; every read beyond the primary key is
 * owner-scoped so the service layer can never load another owner's policy:
 * {@code findByIdAndOwnerSubject} serves single-policy lookups backed by
 * {@code idx_sanitization_policies_owner_subject}, and
 * {@code findByOwnerSubjectOrderByCreatedAtDescIdDesc} serves the owner's
 * policy listing, newest first with the id as a total-order tiebreak. Rule
 * rows are reached only through their owning aggregate.
 */
public interface SanitizationPolicyRepository extends JpaRepository<SanitizationPolicy, UUID> {

    Optional<SanitizationPolicy> findByIdAndOwnerSubject(UUID id, String ownerSubject);

    List<SanitizationPolicy> findByOwnerSubjectOrderByCreatedAtDescIdDesc(String ownerSubject);
}
