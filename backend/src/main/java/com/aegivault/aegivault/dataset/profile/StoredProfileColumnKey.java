package com.aegivault.aegivault.dataset.profile;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Natural identity of one {@link StoredProfileColumn}: the dataset profile
 * it belongs to plus its ordinal in the profiler's deterministic column
 * order.
 *
 * <p>It mirrors the {@code pk_dataset_profile_columns} composite primary
 * key {@code (dataset_id, column_ordinal)} exactly, which is what makes the
 * aggregate invariant "one row per column position of one dataset's
 * profile" enforceable by the database itself.
 *
 * <p>Implements {@link Serializable} and exposes a public no-argument
 * constructor as the specification requires for an id class; equality is
 * structural, so two keys naming the same column compare equal.
 */
public final class StoredProfileColumnKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID datasetId;

    private int columnOrdinal;

    /** Required by the JPA specification for an id class. */
    public StoredProfileColumnKey() {}

    public StoredProfileColumnKey(UUID datasetId, int columnOrdinal) {
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId must not be null");
        this.columnOrdinal = columnOrdinal;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(UUID datasetId) {
        this.datasetId = datasetId;
    }

    public int getColumnOrdinal() {
        return columnOrdinal;
    }

    public void setColumnOrdinal(int columnOrdinal) {
        this.columnOrdinal = columnOrdinal;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StoredProfileColumnKey key)) {
            return false;
        }
        return columnOrdinal == key.columnOrdinal && Objects.equals(datasetId, key.datasetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasetId, columnOrdinal);
    }

    @Override
    public String toString() {
        return "StoredProfileColumnKey[datasetId=" + datasetId + ", columnOrdinal=" + columnOrdinal + "]";
    }
}
