package com.aegivault.aegivault.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * HTTP payload for {@code POST /api/gateway/complete}. Completion inputs
 * only: the model name and the text to inspect and, if allowed, forward.
 *
 * <p>Validation matches the inspect contract exactly: the model bound is
 * the same 255-character label bound, and the content bound reuses the
 * single gateway input-size bound
 * ({@link GatewayInspectRequest#MAX_CONTENT_LENGTH}) rather than
 * redefining it. There is deliberately no {@code actorSubject} or
 * {@code requestId} component — the actor comes from the verified JWT
 * subject and the id is generated server-side.
 *
 * @param model model name the completion targets, never blank, at most
 *        255 characters
 * @param content request text to inspect and forward when allowed, never
 *        null
 */
public record GatewayCompleteRequest(
        @NotBlank @Size(max = 255) String model,
        @NotNull @Size(max = GatewayInspectRequest.MAX_CONTENT_LENGTH) String content) {}
