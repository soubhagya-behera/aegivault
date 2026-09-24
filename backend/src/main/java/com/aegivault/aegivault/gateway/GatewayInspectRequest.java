package com.aegivault.aegivault.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * HTTP payload for {@code POST /api/gateway/inspect}. Inspection inputs
 * only: the model name and the text to inspect.
 *
 * <p>There is deliberately no {@code actorSubject} or {@code requestId}
 * component — the actor comes from the verified JWT subject and the id is
 * generated server-side, so attempted properties are not bound and never
 * used. There are no security-control fields either: this endpoint always
 * inspects under {@link GatewaySecurityPolicy#strict()}.
 *
 * @param model model or provider name the request targets, never blank, at
 *        most 255 characters (the same label bound dataset names and policy
 *        names use)
 * @param content request text to inspect, never null; blank content
 *        inspects {@code ALLOW} with no findings, consistent with
 *        {@link GatewayInspectionRequest}; at most
 *        {@link #MAX_CONTENT_LENGTH} characters, rejected with 400 before
 *        any inspection work begins
 */
public record GatewayInspectRequest(
        @NotBlank @Size(max = 255) String model,
        @NotNull @Size(max = MAX_CONTENT_LENGTH) String content) {

    /**
     * Maximum allowed inspection content in characters (64 KiB). The single
     * gateway input-size bound: enforced by Bean Validation at the request
     * boundary, so oversized requests fail with 400 before PII detection,
     * secret detection, tokenization, or any future provider forwarding.
     * Content is never truncated and never partially inspected.
     */
    public static final int MAX_CONTENT_LENGTH = 65_536;
}
