package com.aegivault.aegivault.gateway.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One persisted gateway usage record: metadata about a single provider
 * invocation that returned a provider response — never prompt or response
 * content.
 *
 * <p>Carries the server-generated gateway request id, the JWT-derived
 * actor, the model, the exact provider-reported token counts (null when
 * the provider supplied none — never estimated), and whether the response
 * was delivered or blocked by response inspection. There are no content,
 * PII, secret, JWT, prompt, or payload columns anywhere: such values have
 * nowhere to be stored.
 *
 * <p>A record is immutable after creation — there are no setters, every
 * column is {@code updatable = false}, and no service offers an update or
 * delete path. One gateway completion request currently makes at most one
 * provider invocation, so {@code request_id} is unique: a repeated request
 * id fails loudly instead of double-counting.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code gateway_usage_records} table
 * (V9); Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "gateway_usage_records")
public class GatewayUsageRecord {

    private static final int ACTOR_MAX = 255;

    private static final int MODEL_MAX = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "request_id", updatable = false, nullable = false)
    private UUID requestId;

    @Column(name = "actor_subject", updatable = false, nullable = false, length = ACTOR_MAX)
    private String actorSubject;

    @Column(name = "model", updatable = false, nullable = false, length = MODEL_MAX)
    private String model;

    @Column(name = "prompt_tokens", updatable = false)
    private Long promptTokens;

    @Column(name = "completion_tokens", updatable = false)
    private Long completionTokens;

    @Column(name = "total_tokens", updatable = false)
    private Long totalTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false, nullable = false, length = 32)
    private GatewayUsageOutcome outcome;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /**
     * Creates one usage record from gateway metadata and exact
     * provider-reported counts.
     *
     * @param requestId server-generated gateway request id, never null
     * @param actorSubject JWT-derived actor, never blank, at most 255
     *        characters
     * @param model completion model, never blank, at most 255 characters
     * @param promptTokens provider-reported prompt tokens, or null when
     *        unknown — never estimated, never a character count
     * @param completionTokens provider-reported completion tokens, or null
     *        when unknown
     * @param totalTokens provider-reported total tokens, or null when
     *        unknown
     * @param outcome what happened to the provider response, never null
     * @throws IllegalArgumentException when any bound is violated
     */
    public GatewayUsageRecord(
            UUID requestId,
            String actorSubject,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            GatewayUsageOutcome outcome) {
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.actorSubject = requireText(actorSubject, "actorSubject", ACTOR_MAX);
        this.model = requireText(model, "model", MODEL_MAX);
        this.promptTokens = requireTokens(promptTokens, "promptTokens");
        this.completionTokens = requireTokens(completionTokens, "completionTokens");
        this.totalTokens = requireTokens(totalTokens, "totalTokens");
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
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

    private static Long requireTokens(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }
}
