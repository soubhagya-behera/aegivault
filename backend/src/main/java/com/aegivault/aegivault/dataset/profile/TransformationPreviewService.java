package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transformation preview over a persisted dataset profile.
 *
 * <p>One call, and nothing else: {@link DatasetProfileService#getProfile}
 * (owner-scoped read of the already-persisted profile — profiling is never
 * re-run, the raw CSV is never opened, sample values are never inspected)
 * mapped through the existing {@link DefaultTransformationPolicy} into a
 * {@link TransformationPreviewResponse}. The service holds no write
 * transaction, creates no policy, creates no run, touches no audit ledger,
 * and modifies neither the dataset, the profile, nor the uploaded CSV.
 *
 * <p>Fail-closed mapping: every detected type resolves through the plan,
 * and a type with no configured strategy surfaces as
 * {@link MissingTransformationException} (names the type only) instead of
 * an invented strategy. All eleven current {@code PiiType} values are
 * covered by the default policy, so that path is defensive only.
 */
@Service
@RequiredArgsConstructor
public class TransformationPreviewService {

    private final DatasetProfileService profiles;

    /**
     * Previews the default transformation strategy for each PII type
     * detected in one owned dataset's persisted profile.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only);
     *        must own the dataset
     * @param datasetId dataset whose persisted profile to preview, never
     *        null
     * @return profile metadata plus strategy recommendations; counts and
     *         rates copied from the stored profile
     * @throws DatasetNotFoundException when the dataset is missing, belongs
     *         to another owner, or has no persisted profile yet (identical
     *         either way)
     * @throws MissingTransformationException when a detected type has no
     *         default strategy (fail-closed; never an invented strategy)
     */
    @Transactional(readOnly = true)
    public TransformationPreviewResponse preview(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        DatasetProfileResponse profile = profiles.getProfile(owner, datasetId);
        return TransformationPreviewResponse.from(profile, DefaultTransformationPolicy.plan());
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
