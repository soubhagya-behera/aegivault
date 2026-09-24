package com.aegivault.aegivault.gateway;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable input to one gateway security inspection: an outbound AI
 * request described with metadata only. The content is inspected in memory
 * and never stored, logged, or returned by the inspection layer.
 *
 * @param requestId correlation id for the request, never null
 * @param actorSubject calling actor, never blank
 * @param model model or provider name the request targets, never blank
 * @param content request text to inspect; null is normalized to empty,
 *        which inspects {@code ALLOW} with no findings because there is
 *        nothing to leak
 */
public record GatewayInspectionRequest(UUID requestId, String actorSubject, String model, String content) {

    public GatewayInspectionRequest {
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (content == null) {
            content = "";
        }
    }
}
