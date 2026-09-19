package com.aegivault.aegivault.dataset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link Dataset}. Standard CRUD comes from
 * {@link JpaRepository}; {@code findByOwnerSubject} serves the per-owner
 * dataset listing backed by {@code idx_datasets_owner_subject}, and
 * {@code findByIdAndOwnerSubject} serves the single owner-scoped lookup so
 * the web layer never loads another owner's row.
 */
public interface DatasetRepository extends JpaRepository<Dataset, UUID> {

    List<Dataset> findByOwnerSubject(String ownerSubject);

    Optional<Dataset> findByIdAndOwnerSubject(UUID id, String ownerSubject);
}
