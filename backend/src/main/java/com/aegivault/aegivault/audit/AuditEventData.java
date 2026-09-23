package com.aegivault.aegivault.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Safe metadata documents for ledger entries. Every document is hand-built
 * JSON with a fixed field order — never a serialized domain object,
 * request, result, failure, or exception — so the bytes are deterministic
 * and contain structural facts only: ids, labels, counts, and closed-vocabulary
 * codes. Raw CSV, sanitized CSV, PII, cell values, passwords, JWTs, API
 * keys, request bodies, and stack traces can never appear here because no
 * such value is ever accepted as an input.
 */
public final class AuditEventData {

    /** Resource type recorded on every sanitization run lifecycle event. */
    public static final String SANITIZATION_RUN_RESOURCE = "SANITIZATION_RUN";

    /** Event recorded after a run row is created and started. */
    public static final String RUN_CREATED = "SANITIZATION_RUN_CREATED";

    /** Event recorded after a run reaches COMPLETED. */
    public static final String RUN_COMPLETED = "SANITIZATION_RUN_COMPLETED";

    /** Event recorded after a run reaches FAILED. */
    public static final String RUN_FAILED = "SANITIZATION_RUN_FAILED";

    private AuditEventData() {
    }

    /**
     * @return {@code {"datasetId":"...","policyName":"...","policyVersion":"..."}},
     *         labels escaped, field order fixed
     */
    public static String runCreated(UUID datasetId, String policyName, String policyVersion) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(policyName, "policyName must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        return "{\"datasetId\":\"" + datasetId + "\",\"policyName\":\"" + escape(policyName)
                + "\",\"policyVersion\":\"" + escape(policyVersion) + "\"}";
    }

    /**
     * @return {@code {"inputRows":N,"outputRows":N,"blankRowsSkipped":N,"columns":N}},
     *         structural counts only, field order fixed
     */
    public static String runCompleted(long inputRows, long outputRows, long blankRowsSkipped, int columns) {
        return "{\"inputRows\":" + inputRows + ",\"outputRows\":" + outputRows
                + ",\"blankRowsSkipped\":" + blankRowsSkipped + ",\"columns\":" + columns + "}";
    }

    /**
     * @return {@code {"errorCode":"...","errorStage":"..."}}, closed-vocabulary
     *         codes only — never the failure message, never a stack trace
     */
    public static String runFailed(String errorCode, String errorStage) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(errorStage, "errorStage must not be null");
        return "{\"errorCode\":\"" + escape(errorCode) + "\",\"errorStage\":\"" + escape(errorStage) + "\"}";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
