package com.aegivault.aegivault.dataset;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link DatasetInput}. The only read is
 * owner-scoped ({@code findByDatasetIdAndOwnerSubject}, backed by
 * {@code idx_dataset_inputs_owner_subject}), so stored bytes can never be
 * opened for another owner's dataset. Dataset metadata queries never touch
 * this repository.
 */
public interface DatasetInputRepository extends JpaRepository<DatasetInput, UUID> {

    Optional<DatasetInput> findByDatasetIdAndOwnerSubject(UUID datasetId, String ownerSubject);
}
