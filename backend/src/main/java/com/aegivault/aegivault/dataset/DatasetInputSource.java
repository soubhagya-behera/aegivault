package com.aegivault.aegivault.dataset;

import java.io.InputStream;
import java.util.UUID;

/**
 * Owner-scoped source of a dataset's stored CSV bytes.
 *
 * <p>Datasets persist metadata only (see {@link Dataset}: names, labels,
 * counts, status); the bytes themselves live in a separate boundary read
 * through this interface. It exists to name the exact seam the future
 * {@code POST /api/runs} endpoint will read through, instead of letting
 * that endpoint invent storage access ad hoc: owner in, caller-owned
 * stream out.
 *
 * <p>Contract for implementations:
 *
 * <ul>
 *   <li>Verify {@code ownerSubject} owns {@code datasetId} through the
 *       existing owner-scoped lookup. A missing dataset and another owner's
 *       dataset must behave identically ({@code DatasetNotFoundException},
 *       generic message).</li>
 *   <li>Return a fresh {@link InputStream} on every call. The caller owns
 *       the stream: it must be closed by the caller, never by the
 *       implementation, and never shared between calls.</li>
 *   <li>Never return null, never expose bytes through any other channel
 *       (no logging, no domain fields, no API bodies), and never fabricate
 *       content for a dataset that has none stored.</li>
 * </ul>
 *
 * <p>The first production implementation is PostgreSQL BYTEA
 * ({@code DatabaseDatasetInputSource}, {@code dataset_inputs} table):
 * owner in, caller-owned stream out. The interface stays neutral so a
 * later backing-store decision changes one implementation, not every
 * caller.
 */
public interface DatasetInputSource {

    /**
     * Opens the stored CSV bytes of one owned dataset.
     *
     * @param ownerSubject calling owner, never blank
     * @param datasetId dataset whose bytes to read, must belong to the owner
     * @return a fresh caller-owned stream, never null
     * @throws DatasetNotFoundException when the dataset is missing or
     *         belongs to another owner
     */
    InputStream openInput(String ownerSubject, UUID datasetId);
}
