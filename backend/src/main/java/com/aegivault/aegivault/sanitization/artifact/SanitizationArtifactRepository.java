package com.aegivault.aegivault.sanitization.artifact;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link SanitizationArtifact}. The only read is
 * owner-scoped ({@code findByRunIdAndOwnerSubject}, backed by
 * {@code idx_sanitization_artifacts_owner_subject}), so one owner's
 * sanitized output can never be opened for another owner. Run metadata
 * queries never touch this repository.
 */
public interface SanitizationArtifactRepository extends JpaRepository<SanitizationArtifact, UUID> {

    Optional<SanitizationArtifact> findByRunIdAndOwnerSubject(UUID runId, String ownerSubject);
}
