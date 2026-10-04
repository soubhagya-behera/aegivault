package com.aegivault.aegivault.sanitization.run;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link SanitizationRun}. Standard CRUD comes from
 * {@link JpaRepository}; every read beyond the primary key is owner-scoped
 * so the service layer can never load another owner's run:
 * {@code findByIdAndOwnerSubject} serves single-run lookups backed by
 * {@code idx_sanitization_runs_owner_subject},
 * {@code findByDatasetIdAndOwnerSubject} serves per-dataset run listings
 * backed by {@code idx_sanitization_runs_dataset_id}, and
 * {@code findByOwnerSubjectOrderByCreatedAtDescIdDesc} serves the owner's
 * full run listing, newest first with the id as a total-order tiebreak.
 */
public interface SanitizationRunRepository extends JpaRepository<SanitizationRun, UUID> {

    Optional<SanitizationRun> findByIdAndOwnerSubject(UUID id, String ownerSubject);

    List<SanitizationRun> findByDatasetIdAndOwnerSubject(UUID datasetId, String ownerSubject);

    List<SanitizationRun> findByOwnerSubjectOrderByCreatedAtDescIdDesc(String ownerSubject);

    /**
     * Database-side existence check for active runs of one source kind against
     * one owned dataset. Used by PostgreSQL binding deletion to refuse removal
     * while a {@code QUEUED} or {@code RUNNING} {@code POSTGRESQL} run exists;
     * terminal runs and runs of another source kind never match.
     */
    boolean existsByDatasetIdAndOwnerSubjectAndSourceTypeAndStatusIn(
            UUID datasetId,
            String ownerSubject,
            SanitizationSourceType sourceType,
            Collection<RunStatus> statuses);
}
