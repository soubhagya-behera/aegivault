package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.pii.PiiType;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Natural identity of one {@link PolicyRule}: the policy it belongs to plus
 * the PII type it configures.
 *
 * <p>It mirrors the {@code pk_sanitization_policy_rules} composite primary
 * key {@code (policy_id, pii_type)} exactly, which is what makes the
 * aggregate invariant "at most one transformation strategy per PII type per
 * policy" enforceable by the database itself: a second row for the same
 * pair cannot be inserted, regardless of the code path that tries.
 *
 * <p>Implements {@link Serializable} and exposes a public no-argument
 * constructor as the specification requires for an id class; equality is
 * structural, so two keys naming the same rule compare equal.
 */
public final class PolicyRuleKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID policyId;

    private PiiType piiType;

    /** Required by the JPA specification for an id class. */
    public PolicyRuleKey() {}

    public PolicyRuleKey(UUID policyId, PiiType piiType) {
        this.policyId = Objects.requireNonNull(policyId, "policyId must not be null");
        this.piiType = Objects.requireNonNull(piiType, "piiType must not be null");
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public void setPolicyId(UUID policyId) {
        this.policyId = policyId;
    }

    public PiiType getPiiType() {
        return piiType;
    }

    public void setPiiType(PiiType piiType) {
        this.piiType = piiType;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PolicyRuleKey key)) {
            return false;
        }
        return Objects.equals(policyId, key.policyId) && piiType == key.piiType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyId, piiType);
    }

    @Override
    public String toString() {
        return "PolicyRuleKey[policyId=" + policyId + ", piiType=" + piiType + "]";
    }
}
