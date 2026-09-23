package com.aegivault.aegivault.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proves the upload path never holds a database transaction while the
 * caller's CSV body is being read.
 *
 * <p>Why this is a security boundary, not a style preference: the body of
 * {@code POST /api/datasets/{id}/input} arrives from the network at the
 * client's pace, up to the shared 10 MiB bound. If that read happens inside
 * a transaction, a pooled connection plus an open database transaction stay
 * pinned for as long as the client cares to take (or forever, for a
 * deliberately slow client), so a handful of concurrent uploads can starve
 * every other request. The bounded read therefore belongs outside any
 * transaction, with only the ownership check and the upsert wrapped in
 * short transactions.
 *
 * <p>Deliberately NOT {@code @Transactional}: the test's own transaction
 * would be active on the reading thread and make the probe meaningless.
 */
@SpringBootTest
class DatasetInputTransactionBoundaryTest {

    private static final String CSV = "name,email\nbob,bob@example.com\n";

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private DatabaseDatasetInputSource inputs;

    @Autowired
    private DatasetInputRepository stored;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    /** Records whether a database transaction was bound to the reading thread. */
    private static final class TransactionProbeInputStream extends InputStream {

        private final ByteArrayInputStream delegate;

        private final AtomicBoolean transactionBoundDuringRead = new AtomicBoolean(false);

        private final AtomicInteger reads = new AtomicInteger();

        private TransactionProbeInputStream(byte[] content) {
            this.delegate = new ByteArrayInputStream(content);
        }

        @Override
        public int read() {
            observe();
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            observe();
            return delegate.read(bytes, offset, length);
        }

        private void observe() {
            reads.incrementAndGet();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionBoundDuringRead.set(true);
            }
        }

        int reads() {
            return reads.get();
        }

        boolean sawTransaction() {
            return transactionBoundDuringRead.get();
        }
    }

    @Test
    void bodyReadHappensOutsideAnyDatabaseTransaction() throws Exception {
        String owner = owner();
        Dataset dataset = datasets.save(new Dataset("customers.csv", owner));
        TransactionProbeInputStream probe =
                new TransactionProbeInputStream(CSV.getBytes(StandardCharsets.UTF_8));

        datasetService.storeInput(owner, dataset.getId(), probe);

        assertThat(probe.reads()).isPositive();
        assertThat(probe.sawTransaction())
                .as("no database transaction may be held while the request body is read")
                .isFalse();

        try (InputStream reopened = inputs.openInput(owner, dataset.getId())) {
            assertThat(new String(reopened.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CSV);
        }
    }

    @Test
    void oversizedInputIsRejectedBeforeAnythingPersistsWithoutHoldingATransaction() {
        String owner = owner();
        Dataset dataset = datasets.save(new Dataset("customers.csv", owner));
        TransactionProbeInputStream probe = new TransactionProbeInputStream(
                new byte[(int) (10L * 1024L * 1024L) + 1]);

        assertThatThrownBy(() -> datasetService.storeInput(owner, dataset.getId(), probe))
                .isInstanceOf(DatasetInputTooLargeException.class)
                .hasMessageContaining("10485760");

        assertThat(probe.sawTransaction())
                .as("an oversized body must be rejected without holding a transaction either")
                .isFalse();
        assertThat(stored.findByDatasetIdAndOwnerSubject(dataset.getId(), owner)).isEmpty();
    }
}
