package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One rule of a {@link SanitizationPolicy}: the transformation strategy the
 * policy applies to exactly one {@link PiiType}.
 *
 * <p>The row carries policy metadata only — an enum name for the PII type,
 * an enum name for the strategy, and the owning policy id. It never holds
 * raw PII values, CSV data, samples, secrets, or credentials, so a policy
 * can be stored, listed, and read back without any data ever travelling
 * with it.
 *
 * <p>The composite primary key {@code (policy_id, pii_type)} is the
 * aggregate invariant enforced by storage: {@link #piiType} is part of the
 * key, so one policy can never hold two strategies for the same PII type.
 * {@code policy_id} is derived identity — it is written through the owning
 * {@link SanitizationPolicy} association via {@code @MapsId}, so it becomes
 * known only once the parent's generated id does.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code sanitization_policy_rules}
 * table (V6); Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "sanitization_policy_rules")
@IdClass(PolicyRuleKey.class)
public class PolicyRule {

    @Id
    @Column(name = "policy_id", updatable = false, nullable = false)
    private UUID policyId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "pii_type", updatable = false, nullable = false, length = 64)
    private PiiType piiType;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "transformation_strategy", nullable = false, length = 64)
    private TransformationStrategy transformationStrategy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "policy_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sanitization_policy_rules_policy"))
    @MapsId("policyId")
    private SanitizationPolicy policy;

    /**
     * @param policy owning policy, never null; supplies the derived {@code policyId}
     * @param piiType PII type this rule configures, never null
     * @param strategy strategy to apply to that type, never null
     */
    public PolicyRule(SanitizationPolicy policy, PiiType piiType, TransformationStrategy strategy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.piiType = Objects.requireNonNull(piiType, "piiType must not be null");
        this.transformationStrategy =
                Objects.requireNonNull(strategy, "transformationStrategy must not be null");
    }
}
