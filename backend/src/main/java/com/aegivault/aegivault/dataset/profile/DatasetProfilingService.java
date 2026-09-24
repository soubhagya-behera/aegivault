package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.dataset.DatasetInputSource;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.csv.CsvDatasetProfiler;
import com.aegivault.aegivault.dataset.csv.CsvParseException;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Explicit dataset profiling: reads one owned dataset's already-uploaded
 * CSV input, profiles it with the existing CSV profiler, and persists the
 * result through the existing profile service.
 *
 * <p>Flow, and nothing else: {@link DatasetInputSource#openInput} (owner in,
 * caller-owned stream out) → {@link CsvDatasetProfiler} (discovery plus the
 * existing bounded profiling sample — no new algorithm, no new limits) →
 * {@link DatasetProfileService#saveProfile} (replace semantics, so a second
 * trigger leaves no stale columns or detections) → re-read through
 * {@link DatasetProfileService#getProfile}, so the response is literally the
 * persisted state in the same shape as {@code GET .../profile}.
 *
 * <p>Boundaries stay where they are: this class holds no CSV parsing, no
 * detection, no persistence, and no transaction of its own. Each composed
 * call commits in its own transaction (the input stream is already an
 * in-memory copy, the engine runs outside any database transaction, and the
 * save is one atomic write), so a profiling failure before the save leaves
 * no partial profile behind. Nothing here modifies the stored CSV bytes.
 *
 * <p>Ownership comes from the verified JWT subject only, threaded through
 * every composed call: a missing dataset, a foreign dataset, and a dataset
 * with no uploaded input all surface as {@link DatasetNotFoundException}
 * (the input boundary's equally-worded signal), rendered as one generic
 * 404. {@link CsvParseException} propagates untouched — its message carries
 * structural facts only — for the controller to render as a safe client
 * error. Nothing is triggered automatically: upload and run creation are
 * untouched.
 */
@Service
@RequiredArgsConstructor
public class DatasetProfilingService {

    private final DatasetInputSource inputs;

    private final CsvDatasetProfiler profiler;

    private final DatasetProfileService profiles;

    /**
     * Profiles the stored CSV input of one owned dataset and persists the
     * result, returning the persisted view.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only);
     *        must own the dataset
     * @param datasetId dataset to profile, never null; must belong to the
     *        owner and have stored input
     * @return the persisted profile view; metadata and counts only
     * @throws DatasetNotFoundException when the dataset is missing, belongs
     *         to another owner, or has no uploaded input yet (identical
     *         either way)
     * @throws CsvParseException when the stored input cannot be discovered
     *         safely
     */
    public DatasetProfileResponse profileStoredInput(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        DatasetProfile profile;
        try (InputStream input = inputs.openInput(owner, datasetId)) {
            profile = profiler.profileCsv(datasetId, input);
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
        profiles.saveProfile(owner, datasetId, profile);
        return profiles.getProfile(owner, datasetId);
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
