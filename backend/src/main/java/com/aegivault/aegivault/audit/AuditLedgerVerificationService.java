package com.aegivault.aegivault.audit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integrity replay for the audit ledger. Reads the whole chain in
 * sequence order and re-derives every entry hash from the stored fields —
 * the stored {@code entryHash} is evidence to check, never ground truth
 * to trust.
 *
 * <p>Detected, in order, per entry: a first sequence other than 1, a
 * sequence gap, a recomputed hash that differs from the stored hash
 * (covers modified event data, a modified previous hash, and a modified
 * entry hash alike), and a previous hash that does not equal the prior
 * entry's stored hash (covers a rewritten link even when both endpoint
 * hashes are internally consistent). The first failure stops the replay;
 * the result names its sequence.
 *
 * <p>A fully rewritten tail — every hash recomputed consistently from
 * forged content — is undetectable by any hash chain; that is the known
 * limit of tamper-evidence without external anchoring, not a gap in this
 * replay.
 */
@Service
@RequiredArgsConstructor
public class AuditLedgerVerificationService {

    private final AuditLedgerEntryRepository entries;

    /**
     * Replays the whole chain oldest-first.
     *
     * @return {@code valid} when the ledger is empty or every entry checks
     *         out, otherwise the first broken entry and its cause; either
     *         way the count of replayed entries travels with the verdict so
     *         callers never report a stale count
     */
    @Transactional(readOnly = true)
    public AuditVerificationResult verify() {
        List<AuditLedgerEntry> chain = entries.findAllByOrderBySequenceNumberAsc();
        if (chain.isEmpty()) {
            return AuditVerificationResult.ok(0L);
        }
        AuditLedgerEntry previous = null;
        for (AuditLedgerEntry entry : chain) {
            if (previous == null && entry.getSequenceNumber() != 1L) {
                return AuditVerificationResult.broken(
                        "FIRST_SEQUENCE_MUST_BE_ONE", entry.getSequenceNumber(), chain.size());
            }
            if (previous != null && entry.getSequenceNumber() != previous.getSequenceNumber() + 1L) {
                return AuditVerificationResult.broken(
                        "SEQUENCE_GAP", entry.getSequenceNumber(), chain.size());
            }
            String recomputed = AuditEntryHasher.hash(
                    entry.getSequenceNumber(), entry.getEventType(), entry.getActorSubject(),
                    entry.getResourceType(), entry.getResourceId(), entry.getEventData(),
                    entry.getPreviousHash());
            if (!recomputed.equals(entry.getEntryHash())) {
                return AuditVerificationResult.broken(
                        "ENTRY_HASH_MISMATCH", entry.getSequenceNumber(), chain.size());
            }
            if (previous != null && !entry.getPreviousHash().equals(previous.getEntryHash())) {
                return AuditVerificationResult.broken(
                        "PREVIOUS_HASH_LINK_BROKEN", entry.getSequenceNumber(), chain.size());
            }
            previous = entry;
        }
        return AuditVerificationResult.ok(chain.size());
    }
}
