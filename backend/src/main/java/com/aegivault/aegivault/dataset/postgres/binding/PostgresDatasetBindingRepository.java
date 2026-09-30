package com.aegivault.aegivault.dataset.postgres.binding;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link PostgresDatasetBinding}. Standard CRUD comes
 * from {@link JpaRepository}.
 *
 * <p><strong>Every lookup is owner-scoped.</strong> There is no
 * {@code findById}-based read path exposed for callers, because an unscoped
 * read would let one owner's binding be reached through another owner's dataset
 * id. {@link #findByDatasetIdAndOwnerSubject} is the only read the service uses,
 * which makes "a foreign binding looks exactly like a missing one" structural
 * rather than a rule the caller has to remember.
 *
 * <p>The primary key is {@code dataset_id}, so the at-most-one-binding-per-dataset
 * rule is a database guarantee; no repository method has to implement it.
 */
public interface PostgresDatasetBindingRepository
        extends JpaRepository<PostgresDatasetBinding, UUID> {

    Optional<PostgresDatasetBinding> findByDatasetIdAndOwnerSubject(UUID datasetId, String ownerSubject);
}
