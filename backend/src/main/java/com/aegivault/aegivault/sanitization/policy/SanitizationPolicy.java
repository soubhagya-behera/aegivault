package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Aggregate root of one reusable, owner-scoped sanitization policy: who owns
 * it ({@code ownerSubject}, from the JWT subject only), how it is labelled
 * (name, version, optional description), and which transformation strategy
 * applies to which {@link PiiType}.
 *
 * <p>The aggregate preserves the invariant that one policy maps each
 * {@link PiiType} to at most one {@link TransformationStrategy}: rules are
 * built through the existing {@link TransformationPlan#of(List)}, which
 * rejects duplicates and null entries, and the storage primary key
 * {@code (policy_id, pii_type)} rejects the same pair again at the
 * database. An empty rule set is rejected as well — a policy with no
 * decision to apply would silently change nothing while looking valid.
 *
 * <p>Construction stores rules in alphabetical {@link PiiType} order (the
 * same deterministic order {@code PolicySnapshot} renders with), so the
 * persisted order, the read order ({@code @OrderBy}), and the API response
 * order can never disagree.
 *
 * <p>A policy carries metadata only: names, versions, enum names, and
 * timestamps. It never holds raw PII values, CSV data, samples, secrets, or
 * stack traces, and its labels are bounded (255/255/1024) at construction,
 * at the API boundary, and by schema CHECKs — one bound, three enforcement
 * points.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code sanitization_policies} table
 * (V6); Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "sanitization_policies")
public class SanitizationPolicy {

    private static final int NAME_MAX = 255;

    private static final int VERSION_MAX = 255;

    private static final int DESCRIPTION_MAX = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    @Column(name = "name", nullable = false, length = NAME_MAX)
    private String name;

    @Column(name = "version", nullable = false, length = VERSION_MAX)
    private String version;

    @Column(name = "description", length = DESCRIPTION_MAX)
    private String description;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("piiType ASC")
    private List<PolicyRule> rules = new ArrayList<>();

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Creates a policy in its final form: nothing about a stored policy
     * changes after creation (there is no update path yet), so all state is
     * set here.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only)
     * @param name human label, never blank, at most 255 characters
     * @param version version label, never blank, at most 255 characters
     * @param description optional free text, null or at most 1024 characters
     * @param rules explicit rules, never null, never empty, no null entries,
     *        no duplicate PII types
     * @throws IllegalArgumentException when a label is blank or too long, or
     *         when the rule set is empty (duplicates and nulls are rejected
     *         by {@link TransformationPlan#of(List)})
     */
    public SanitizationPolicy(
            String ownerSubject,
            String name,
            String version,
            String description,
            List<TransformationRule> rules) {
        this.ownerSubject = requireText(ownerSubject, "ownerSubject", NAME_MAX);
        this.name = requireText(name, "name", NAME_MAX);
        this.version = requireText(version, "version", VERSION_MAX);
        this.description = normalizeDescription(description);
        Map<PiiType, TransformationStrategy> plan = TransformationPlan.of(rules).strategies();
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("policy must cover at least one PII type");
        }
        Map<PiiType, TransformationStrategy> ordered =
                new TreeMap<>(Comparator.comparing(PiiType::name));
        ordered.putAll(plan);
        for (Map.Entry<PiiType, TransformationStrategy> entry : ordered.entrySet()) {
            this.rules.add(new PolicyRule(this, entry.getKey(), entry.getValue()));
        }
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException(
                    "description must be at most " + DESCRIPTION_MAX + " characters");
        }
        return trimmed;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

