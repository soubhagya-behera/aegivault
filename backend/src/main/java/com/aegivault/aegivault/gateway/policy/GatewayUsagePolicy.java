package com.aegivault.aegivault.gateway.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One owner-scoped gateway usage limit definition: who owns it
 * ({@code ownerSubject}, the JWT subject only), how it is labelled, the
 * request and token quantity limits it declares, and whether it is enabled.
 *
 * <p>A limit is either a strictly positive count or absent (null) when the
 * policy does not constrain it. Zero and negative limits are rejected here
 * and by schema CHECKs: a zero limit is a contradiction, not a limit, and
 * null already expresses "unconstrained". At least one limit must be
 * supplied — a policy that constrains nothing would look valid while
 * changing nothing.
 *
 * <p>These rows are inert. This milestone is the persisted definition only:
 * no gateway code path reads this entity, so it has zero runtime effect on
 * traffic, and {@code enabled} records intent rather than behavior. There
 * are deliberately no pricing, currency, or cost fields — a policy
 * expresses request and token quantities only.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code gateway_usage_policies} table
 * (V10); Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "gateway_usage_policies")
public class GatewayUsagePolicy {

    private static final int OWNER_MAX = 255;

    private static final int NAME_MAX = 255;

    private static final int DESCRIPTION_MAX = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_subject", updatable = false, nullable = false, length = OWNER_MAX)
    private String ownerSubject;

    @Column(name = "name", nullable = false, length = NAME_MAX)
    private String name;

    @Column(name = "description", length = DESCRIPTION_MAX)
    private String description;

    @Column(name = "requests_per_minute")
    private Long requestsPerMinute;

    @Column(name = "requests_per_day")
    private Long requestsPerDay;

    @Column(name = "tokens_per_day")
    private Long tokensPerDay;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;


    /**
     * Creates a policy with its initial label, limits, and enabled state.
     *
     * @param ownerSubject calling owner, never blank (the JWT subject only)
     * @param name human label, never blank, at most 255 characters
     * @param description optional free text, null or at most 1024 characters
     * @param requestsPerMinute declared requests per minute, null or strictly
     *        positive
     * @param requestsPerDay declared requests per day, null or strictly
     *        positive
     * @param tokensPerDay declared tokens per day, null or strictly positive
     * @param enabled whether the definition is switched on
     * @throws IllegalArgumentException when a label is blank or too long, a
     *         limit is not strictly positive, or no limit is supplied at all
     */
    public GatewayUsagePolicy(
            String ownerSubject,
            String name,
            String description,
            Long requestsPerMinute,
            Long requestsPerDay,
            Long tokensPerDay,
            boolean enabled) {
        this.ownerSubject = requireText(ownerSubject, "ownerSubject", OWNER_MAX);
        this.name = requireText(name, "name", NAME_MAX);
        this.description = normalizeDescription(description);
        this.requestsPerMinute = requirePositive(requestsPerMinute, "requestsPerMinute");
        this.requestsPerDay = requirePositive(requestsPerDay, "requestsPerDay");
        this.tokensPerDay = requirePositive(tokensPerDay, "tokensPerDay");
        if (requestsPerMinute == null && requestsPerDay == null && tokensPerDay == null) {
            throw new IllegalArgumentException("policy must define at least one limit");
        }
        this.enabled = enabled;
    }

    /**
     * Replaces this policy's label, limits, and enabled state in place. The
     * policy id and owner never change and no new row is created.
     *
     * <p>Every value is validated before anything is mutated, so a rejected
     * update leaves the aggregate exactly as it was.
     *
     * @param name human label, never blank, at most 255 characters
     * @param description optional free text, null or at most 1024 characters
     * @param requestsPerMinute declared requests per minute, null or strictly
     *        positive
     * @param requestsPerDay declared requests per day, null or strictly
     *        positive
     * @param tokensPerDay declared tokens per day, null or strictly positive
     * @param enabled whether the definition is switched on
     * @throws IllegalArgumentException when a label is blank or too long, a
     *         limit is not strictly positive, or no limit is supplied at all
     */
    public void update(
            String name,
            String description,
            Long requestsPerMinute,
            Long requestsPerDay,
            Long tokensPerDay,
            boolean enabled) {
        String validName = requireText(name, "name", NAME_MAX);
        String validDescription = normalizeDescription(description);
        Long validPerMinute = requirePositive(requestsPerMinute, "requestsPerMinute");
        Long validPerDay = requirePositive(requestsPerDay, "requestsPerDay");
        Long validTokensPerDay = requirePositive(tokensPerDay, "tokensPerDay");
        if (validPerMinute == null && validPerDay == null && validTokensPerDay == null) {
            throw new IllegalArgumentException("policy must define at least one limit");
        }
        this.name = validName;
        this.description = validDescription;
        this.requestsPerMinute = validPerMinute;
        this.requestsPerDay = validPerDay;
        this.tokensPerDay = validTokensPerDay;
        this.enabled = enabled;
        this.updatedAt = Instant.now();
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

    private static Long requirePositive(Long value, String field) {
        if (value != null && value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive when present");
        }
        return value;
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
