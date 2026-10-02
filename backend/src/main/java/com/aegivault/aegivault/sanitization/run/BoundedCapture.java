package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.sanitization.artifact.ArtifactTooLargeException;
import com.aegivault.aegivault.sanitization.artifact.DatabaseArtifactStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Forwards every byte to a caller's stream while retaining a bounded copy for
 * artifact storage.
 *
 * <p><strong>The bound is the single artifact bound, not a new one.</strong>
 * Beyond {@link DatabaseArtifactStore#MAX_ARTIFACT_BYTES} this stream aborts
 * with {@link ArtifactTooLargeException}, so no truncation and no partial
 * artifact are ever possible. A run whose output grows past the bound fails
 * cleanly instead of storing a silently shortened file.
 *
 * <p><strong>Nothing caller-owned is ever closed.</strong> The stream flushes
 * through to the downstream stream but never closes it, and
 * {@link #captured()} hands back an independent copy that the caller owns. The
 * buffer cannot exceed the artifact bound, so this is not a second unbounded
 * copy of the output.
 */
final class BoundedCapture extends OutputStream {

    private final OutputStream downstream;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private long total;

    BoundedCapture(OutputStream downstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream must not be null");
    }

    @Override
    public void write(int singleByte) throws IOException {
        write(new byte[] {(byte) singleByte}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        total += length;
        if (total > DatabaseArtifactStore.MAX_ARTIFACT_BYTES) {
            throw new ArtifactTooLargeException(DatabaseArtifactStore.MAX_ARTIFACT_BYTES);
        }
        downstream.write(bytes, offset, length);
        captured.write(bytes, offset, length);
    }

    @Override
    public void flush() throws IOException {
        downstream.flush();
    }

    byte[] captured() {
        return captured.toByteArray();
    }
}