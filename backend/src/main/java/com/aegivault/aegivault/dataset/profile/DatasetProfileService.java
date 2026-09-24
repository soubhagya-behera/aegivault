package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped profile persistence. The invariant is
 * {@code JWT sub -> ownerSubject -> owner-scoped repository query}: USER and
 * ADMIN behave identically, no cross-user access exists here, and a foreign
 * dataset id is indistinguishable from a missing one.
 *
 * <p>The service stores and reads the {@link StoredDatasetProfile}
 * aggregate only. It never runs profiling itself — callers hand it an
 * already-computed {@link DatasetProfile} — and it never touches CSV
 * reading, detection, transformation, runs, artifacts, policies, or audit
 * code: those boundaries stay where they are. Nothing in this milestone
 * calls this service automatically; it exists so the next milestone can
 * wire profiling into the dataset workflow explicitly.
 */
@Service
@RequiredArgsConstructor
public class DatasetProfileService {

    private final StoredDatasetProfileRepository profiles;

    private final DatasetRepository datasets;

    /**
     * Stores (or replaces) the profile of an owned dataset in one
     * transaction. A re-save deletes the previous profile row — its columns
     * and detections vanish through the cascade — flushes, then stores the
     * new aggregate under the same dataset id: same owner, no new profile
     * row, and no stale column or detection rows survive. The delete and
     * the insert are separated by a flush because clearing and re-adding
     * child rows with the same {@code (dataset_id, column_ordinal)} keys in
     * one persistence context would associate two objects with the same
     * identifier.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only);
     *        must own the dataset
     * @param datasetId dataset the profile describes, never null
     * @param profile profiler result, never null; stored verbatim
     * @throws DatasetNotFoundException when the dataset is missing or
     *         belongs to another owner (identical either way)
     */
    @Transactional
    public void saveProfile(String ownerSubject, UUID datasetId, DatasetProfile profile) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        datasets.findByIdAndOwnerSubject(datasetId, owner).orElseThrow(DatasetNotFoundException::new);
        profiles.findById(datasetId).ifPresent(profiles::delete);
        profiles.flush();
        profiles.save(new StoredDatasetProfile(datasetId, owner, profile));
    }

    /**
     * Reads the stored profile of an owned dataset. The persisted profile
     * represents the profiler result at save time; profiling is never
     * re-run here.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only)
     * @param datasetId dataset the profile describes, never null
     * @return the stored profile view; metadata and counts only
     * @throws DatasetProfileNotFoundException when the dataset is missing,
     *         belongs to another owner, or has no persisted profile yet
     *         (identical 404 either way)
     */
    @Transactional(readOnly = true)
    public DatasetProfileResponse getProfile(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        StoredDatasetProfile stored = profiles
                .findByDatasetIdAndOwnerSubject(datasetId, owner)
                .orElseThrow(DatasetProfileNotFoundException::new);
        return DatasetProfileResponse.from(stored.toProfile());
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
