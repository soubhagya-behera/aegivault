package com.aegivault.aegivault.sanitization.run;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, detached description of one persisted {@link SanitizationRun} that
 * a worker may execute: its id, the dataset it was created against, the
 * {@code ownerSubject} recorded on the run row itself, the policy labels and
 * frozen snapshot, and its current {@link RunStatus}.
 *
 * <p><strong>It is a read model, not a new job.</strong> The run row already is
 * the persisted execution state; this type exists only so a background worker
 * can act on a run without holding a JPA entity (and without a second
 * persistence concept). It is never written anywhere.
 *
 * <p><strong>The owner comes from the run, never from a caller.</strong> The
 * owner is copied off the persisted row, so a job cannot be pointed at another
 * actor's data by passing a different owner — there is no owner parameter to
 * pass. This is the only shape in which {@code ownerSubject} leaves the
 * service layer, and it does so only as the run's own recorded owner.
 *
 * <p>It carries operation metadata only: no CSV content, no PII, no samples,
 * no secrets.
 *
 * @param id run id, never null
 * @param ownerSubject owner recorded on the run row, never blank
 * @param datasetId dataset the run was created against, never null
 * @param status the run's current status, never null
 * @param policyName policy label frozen at creation, never blank
 * @param policyVersion policy version label frozen at creation, never blank
 * @param policySnapshot canonical frozen policy text, never blank
 */
public record SanitizationRunTarget(
        UUID id,
        String ownerSubject,
        UUID datasetId,
        RunStatus status,
        String policyName,
        String policyVersion,
        String policySnapshot) {

    public SanitizationRunTarget {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        if (policyName == null || policyName.isBlank()) {
            throw new IllegalArgumentException("policyName must not be blank");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
        if (policySnapshot == null || policySnapshot.isBlank()) {
            throw new IllegalArgumentException("policySnapshot must not be blank");
        }
    }
}
