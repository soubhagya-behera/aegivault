package com.aegivault.aegivault.dataset;

import com.aegivault.aegivault.dataset.csv.CsvLimits;
import com.aegivault.aegivault.dataset.csv.CsvParseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-BYTEA {@link DatasetInputSource}: stores and serves one
 * dataset's CSV input bytes.
 *
 * <p>Bounds: a single limit, {@link CsvLimits#DEFAULT_MAX_INPUT_BYTES}
 * (10 MiB) — the same constant the CSV engine enforces, not a second one.
 * Oversized input is rejected while streaming, before any row is persisted;
 * nothing is truncated and no partial input is ever stored. Stored bytes
 * are opaque here: CSV validation stays in the discovery/sanitization
 * layer, so even empty input stores as-is.
 *
 * <p>Each opened stream is a fresh {@link ByteArrayInputStream} over an
 * independent copy of the stored bytes: callers can read and close freely,
 * and closing never affects the caller (nothing caller-owned is ever
 * closed here) or the persistence context.
 *
 * <p>Transaction boundary: the bounded byte read happens outside any
 * database transaction. Only the ownership check and the upsert run in
 * (short) transactions. The reason is availability, not style — the
 * caller's stream is client-paced, so a read performed inside a
 * transaction would pin a pooled connection and an open transaction for as
 * long as the client takes (indefinitely, for a deliberately slow client),
 * and a handful of such uploads would starve every other request. The
 * ownership check stays ahead of the read so a missing or foreign dataset
 * is still rejected without reading its body, and it is repeated inside the
 * write transaction so check and write commit together.
 */
@Service
@RequiredArgsConstructor
public class DatabaseDatasetInputSource implements DatasetInputSource {

    private final DatasetRepository datasets;

    private final DatasetInputRepository inputs;

    private final TransactionTemplate transactions;

    /**
     * Stores (or replaces) the input bytes of an owned dataset.
     *
     * @param ownerSubject calling owner, never blank; must own the dataset
     * @param datasetId dataset to attach the bytes to, must belong to the owner
     * @param input bytes to store, never null; read but never closed here
     * @throws DatasetNotFoundException when the dataset is missing or
     *         belongs to another owner
     * @throws DatasetInputTooLargeException when the input exceeds 10 MiB;
     *         nothing is persisted in that case
     */
    public void storeInput(String ownerSubject, UUID datasetId, InputStream input) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        requireOwnedDataset(owner, datasetId);
        byte[] content = readBounded(input);
        transactions.executeWithoutResult(status -> storeContent(owner, datasetId, content));
    }

    @Override
    @Transactional(readOnly = true)
    public InputStream openInput(String ownerSubject, UUID datasetId) {
        String owner = requireOwner(ownerSubject);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        DatasetInput stored = inputs
                .findByDatasetIdAndOwnerSubject(datasetId, owner)
                .orElseThrow(DatasetNotFoundException::new);
        return new ByteArrayInputStream(stored.contentCopy());
    }

    /**
     * Owner-scoped existence check; a missing dataset and another owner's
     * dataset behave identically. Runs in its own short transaction, before
     * the caller's body is read.
     */
    private void requireOwnedDataset(String owner, UUID datasetId) {
        datasets.findByIdAndOwnerSubject(datasetId, owner).orElseThrow(DatasetNotFoundException::new);
    }

    /**
     * One transaction around the check, the upsert, and the commit, so the
     * stored bytes always belong to an owned dataset at commit time.
     */
    private void storeContent(String owner, UUID datasetId, byte[] content) {
        requireOwnedDataset(owner, datasetId);
        inputs.findByDatasetIdAndOwnerSubject(datasetId, owner)
                .ifPresentOrElse(
                        existing -> existing.replaceContent(content),
                        () -> inputs.save(new DatasetInput(datasetId, owner, content)));
    }

    private static byte[] readBounded(InputStream input) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > CsvLimits.DEFAULT_MAX_INPUT_BYTES) {
                    throw new DatasetInputTooLargeException(CsvLimits.DEFAULT_MAX_INPUT_BYTES);
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
    }

    private static String requireOwner(String ownerSubject) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("ownerSubject must not be blank");
        }
        return ownerSubject.trim();
    }
}
