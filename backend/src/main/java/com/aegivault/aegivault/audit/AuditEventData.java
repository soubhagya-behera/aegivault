package com.aegivault.aegivault.audit;

import java.util.List;
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

    /** Event recorded after a gateway inspection returns ALLOW. */
    public static final String GATEWAY_INSPECTION_ALLOWED = "AI_GATEWAY_INSPECTION_ALLOWED";

    /** Event recorded after a gateway inspection returns BLOCK. */
    public static final String GATEWAY_INSPECTION_BLOCKED = "AI_GATEWAY_INSPECTION_BLOCKED";

    /** Resource type recorded on every gateway inspection event. */
    public static final String AI_GATEWAY_INSPECTION_RESOURCE = "AI_GATEWAY_INSPECTION";

    /**
     * Event recorded when an enabled usage policy admitted a request.
     *
     * <p>Deliberately distinct from
     * {@link #GATEWAY_INSPECTION_ALLOWED}: that event means a security
     * inspection passed, this one means a quota check passed. A request
     * produces both at different moments, and conflating them would make an
     * admitted-but-inspected request look like one decision instead of two.
     */
    public static final String GATEWAY_USAGE_POLICY_ALLOWED = "GATEWAY_USAGE_POLICY_ALLOWED";

    /**
     * Event recorded when an enabled usage policy refused a request.
     *
     * <p>No inspection event exists for the same request: admission runs first,
     * so a refused request is never inspected and
     * {@link #GATEWAY_INSPECTION_BLOCKED} must not be used to describe it. A
     * policy refusal is a quota decision, not a security verdict.
     */
    public static final String GATEWAY_USAGE_POLICY_REJECTED = "GATEWAY_USAGE_POLICY_REJECTED";

    /** Resource type recorded on every gateway usage policy event. */
    public static final String GATEWAY_USAGE_POLICY_RESOURCE = "GATEWAY_USAGE_POLICY";

    /**
     * Event recorded after a gateway usage policy definition is created.
     *
     * <p>Deliberately distinct from
     * {@link #GATEWAY_USAGE_POLICY_ALLOWED} /
     * {@link #GATEWAY_USAGE_POLICY_REJECTED}: those are runtime enforcement
     * decisions about one request, these record a change to the policy
     * definition itself. A policy that no request ever consults still has a
     * lifecycle, and reusing an enforcement event type would make "a
     * definition was created" indistinguishable from "a quota check passed".
     */
    public static final String GATEWAY_USAGE_POLICY_CREATED = "GATEWAY_USAGE_POLICY_CREATED";

    /** Event recorded after a gateway usage policy definition is updated. */
    public static final String GATEWAY_USAGE_POLICY_UPDATED = "GATEWAY_USAGE_POLICY_UPDATED";

    /** Event recorded after a gateway usage policy definition is deleted. */
    public static final String GATEWAY_USAGE_POLICY_DELETED = "GATEWAY_USAGE_POLICY_DELETED";

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

    /**
     * @return {@code {"model":"...","verdict":"...","reasons":[...],"detectedPiiTypes":[...]}},
     *         labels escaped, field order fixed, code lists already ordered
     *         by the caller — safe metadata only, never request content,
     *         matched values, or secrets
     */
    public static String gatewayInspection(
            String model, String verdict, List<String> reasons, List<String> detectedPiiTypes) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(reasons, "reasons must not be null");
        Objects.requireNonNull(detectedPiiTypes, "detectedPiiTypes must not be null");
        return "{\"model\":\"" + escape(model) + "\",\"verdict\":\"" + escape(verdict)
                + "\",\"reasons\":" + codes(reasons) + ",\"detectedPiiTypes\":" + codes(detectedPiiTypes) + "}";
    }

    /**
     * Metadata for a policy decision that <strong>admitted</strong> a request.
     *
     * @param decision the closed-vocabulary decision code
     * @param enforcedWindows the request windows that were actually checked,
     *        already ordered by the caller so the serialized bytes are stable
     * @return {@code {"decision":"...","enforcedWindows":[...]}}, fixed field
     *         order, closed-vocabulary values only
     */
    public static String gatewayUsagePolicyAllowed(
            String decision, List<String> enforcedWindows) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(enforcedWindows, "enforcedWindows must not be null");
        return "{\"decision\":\"" + escape(decision) + "\",\"enforcedWindows\":" + codes(enforcedWindows) + "}";
    }

    /**
     * Metadata for a policy decision that <strong>refused</strong> a request.
     *
     * <p>Names only the exhausted window, never the counts, the configured
     * limits, or the counter: the ledger records which window decided, not how
     * much was used or how much was allowed.
     *
     * @param decision the closed-vocabulary decision code
     * @param rejectedWindow the window that refused, a closed-vocabulary code
     * @return {@code {"decision":"...","rejectedWindow":"..."}}, fixed field
     *         order, closed-vocabulary values only
     */
    public static String gatewayUsagePolicyRejected(String decision, String rejectedWindow) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(rejectedWindow, "rejectedWindow must not be null");
        return "{\"decision\":\"" + escape(decision) + "\",\"rejectedWindow\":\"" + escape(rejectedWindow) + "\"}";
    }

    /**
     * Metadata for a gateway usage policy lifecycle change.
     *
     * <p>Intentionally the smallest document that still proves the change
     * happened: one closed-vocabulary action code. The ledger must evidence
     * <em>that</em> a policy changed, not copy the policy, so the label,
     * description, request/token limits, enabled state, owner subject, and
     * the raw request body are all deliberately absent — they are already
     * recoverable from the policy row itself, and copying them would widen
     * the blast radius of the ledger for no audit value. The actor is never
     * repeated here either: the ledger stores it as its own column.
     *
     * @param action the closed-vocabulary action code
     * @return {@code {"action":"..."}}, fixed field order, one field only
     */
    public static String gatewayUsagePolicyLifecycle(String action) {
        Objects.requireNonNull(action, "action must not be null");
        return "{\"action\":\"" + escape(action) + "\"}";
    }

    private static String codes(List<String> codes) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"").append(escape(Objects.requireNonNull(codes.get(i), "code must not be null"))).append("\"");
        }
        return json.append("]").toString();
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
