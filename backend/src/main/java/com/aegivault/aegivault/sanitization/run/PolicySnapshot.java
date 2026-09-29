package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable persistence representation of the transformation policy a
 * {@link SanitizationRun} was created with.
 *
 * <p>A run must stay explainable after the application's current policy
 * changes, so the run never points at "whatever the default policy happens
 * to be". Instead the full type-to-strategy mapping is frozen into the run
 * row as canonical JSON via {@link #toJson()}: one entry per covered
 * {@link PiiType}, keys sorted alphabetically by type name, so the stored
 * text is deterministic regardless of enum declaration order or map
 * iteration order.
 *
 * <p>The snapshot carries policy metadata only: type names, strategy names,
 * and the caller-supplied policy labels. It never contains data values,
 * samples, secrets, or credentials.
 */
public final class PolicySnapshot {

    /**
     * Shared reader for the canonical form. A single stateless mapper is enough
     * here, and it is private: nothing outside this type may interpret the
     * stored snapshot text.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String policyName;

    private final String policyVersion;

    private final Map<PiiType, TransformationStrategy> rules;

    private PolicySnapshot(String policyName, String policyVersion, Map<PiiType, TransformationStrategy> rules) {
        this.policyName = policyName;
        this.policyVersion = policyVersion;
        this.rules = rules;
    }

    /**
     * Freezes one plan into an immutable snapshot.
     *
     * @param policyName human label for the policy, e.g. {@code "default"},
     *        never blank; stored as a label only, never resolved later
     * @param policyVersion caller-assigned version label, e.g. {@code "v1"},
     *        never blank; the snapshot JSON itself is the authoritative
     *        record, the version is an audit convenience
     * @param plan explicit plan to freeze, never null, must cover at least
     *        one PII type
     * @return the immutable snapshot
     */
    public static PolicySnapshot fromPlan(String policyName, String policyVersion, TransformationPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        if (plan.strategies().isEmpty()) {
            throw new IllegalArgumentException("plan must cover at least one PII type");
        }
        return new PolicySnapshot(
                requireText(policyName, "policyName"),
                requireText(policyVersion, "policyVersion"),
                Map.copyOf(plan.strategies()));
    }

    public String policyName() {
        return policyName;
    }

    public String policyVersion() {
        return policyVersion;
    }

    /**
     * @return the frozen rules, unmodifiable
     */
    public Map<PiiType, TransformationStrategy> rules() {
        return rules;
    }

    /**
     * Renders the canonical stored form:
     * {@code {"policyName":"...","policyVersion":"...","rules":{"ADDRESS":"REDACT",...}}}.
     * Rules are ordered alphabetically by PII type name, so two snapshots of
     * the same mapping always produce identical text. Hand-built instead of
     * Jackson so the persisted form can never shift with mapper settings;
     * only enum names and the escaped labels appear, so no raw data can leak
     * through this method.
     *
     * @return deterministic JSON text, never blank
     */
    public String toJson() {
        Map<PiiType, TransformationStrategy> ordered =
                new TreeMap<>(Comparator.comparing(PiiType::name));
        ordered.putAll(rules);
        StringBuilder json = new StringBuilder("{\"policyName\":\"");
        json.append(escape(policyName));
        json.append("\",\"policyVersion\":\"");
        json.append(escape(policyVersion));
        json.append("\",\"rules\":{");
        boolean first = true;
        for (Map.Entry<PiiType, TransformationStrategy> entry : ordered.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey().name()).append("\":\"");
            json.append(entry.getValue().name()).append('"');
        }
        json.append("}}");
        return json.toString();
    }

    /**
     * Parses the canonical stored form back into a snapshot.
     *
     * <p>This is the exact inverse of {@link #toJson()} and lives beside it, so
     * the stored format has one owner and can never be interpreted two
     * different ways. It exists so a background worker can execute exactly the
     * policy that was frozen at creation, from the run row alone, instead of
     * depending on a caller still holding the original plan object.
     *
     * <p><strong>Parsed strictly.</strong> A missing label, an empty or absent
     * rule set, an unknown PII type, or an unknown strategy name is rejected
     * rather than skipped or defaulted: a snapshot that cannot be read exactly
     * must not become a plan that quietly covers less than the run promised.
     * No data values can appear here — the format only ever holds enum names
     * and the two labels.
     *
     * @param json canonical snapshot text, never null or blank
     * @return the parsed snapshot, never null
     * @throws IllegalArgumentException when the text is not a readable snapshot
     */
    public static PolicySnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("policy snapshot must not be blank");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("policy snapshot must be valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("policy snapshot must be a JSON object");
        }
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isObject() || rules.isEmpty()) {
            throw new IllegalArgumentException("policy snapshot must carry at least one rule");
        }
        Map<PiiType, TransformationStrategy> parsed = new EnumMap<>(PiiType.class);
        rules.properties().forEach(entry -> parsed.put(
                piiType(entry.getKey()), strategy(entry.getKey(), entry.getValue().asText())));
        return new PolicySnapshot(
                text(root, "policyName"),
                text(root, "policyVersion"),
                Map.copyOf(parsed));
    }

    /**
     * The executable plan this snapshot froze.
     *
     * <p>Derived from the frozen rules alone, so the plan a run executes later
     * is the plan it was created with, whatever the application's current
     * default policy happens to be.
     *
     * @return an immutable plan covering exactly the frozen rules
     */
    public TransformationPlan toPlan() {
        return new TransformationPlan(rules);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw new IllegalArgumentException("policy snapshot must carry " + field);
        }
        return value.asText();
    }

    private static PiiType piiType(String name) {
        try {
            return PiiType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("policy snapshot names an unknown PII type");
        }
    }

    private static TransformationStrategy strategy(String piiTypeName, String name) {
        try {
            return TransformationStrategy.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "policy snapshot names an unknown strategy for " + piiTypeName);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
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
