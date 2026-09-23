package com.aegivault.aegivault.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Safe view of one ledger verification: the verdict, how many entries the
 * replay examined, and — only when invalid — the machine-readable failure
 * code. Never event data, actor subjects, resource ids, hashes, raw rows,
 * or exception text: a verifier learns whether the chain holds, nothing
 * about what it contains.
 *
 * @param valid whether every replayed entry checked out
 * @param entriesChecked how many entries the replay examined
 * @param failureCode machine-readable cause when invalid, absent when valid
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditVerifyResponse(boolean valid, long entriesChecked, String failureCode) {

    static AuditVerifyResponse from(AuditVerificationResult result) {
        return new AuditVerifyResponse(
                result.valid(), result.entriesChecked(), result.valid() ? null : result.failureReason());
    }
}
