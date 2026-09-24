package com.aegivault.aegivault.dataset.profile;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link StoredDatasetProfile}. The primary key is
 * the profiled dataset id, so at most one stored profile exists per
 * dataset; {@code findByDatasetIdAndOwnerSubject} serves the single
 * owner-scoped lookup so the web layer never loads another owner's row.
 */
public interface StoredDatasetProfileRepository extends JpaRepository<StoredDatasetProfile, UUID> {

    Optional<StoredDatasetProfile> findByDatasetIdAndOwnerSubject(UUID datasetId, String ownerSubject);
}
