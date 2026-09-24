package com.aegivault.aegivault.dataset.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creation payload for
 * {@code POST /api/datasets/{datasetId}/profile/transformation-preview/policy}.
 * Policy labels only — the rules are derived server-side from the persisted
 * profile and the default transformation plan, never from the request — so
 * this payload carries no rules, no CSV data, and no PII values.
 *
 * <p>Bounds mirror {@code POST /api/policies} labels exactly, and there is
 * deliberately no {@code ownerSubject} component: ownership comes from the
 * verified JWT subject only.
 *
 * @param name human label, never blank, at most 255 characters
 * @param version version label, never blank, at most 255 characters
 * @param description optional free text, null or at most 1024 characters
 */
public record CreatePolicyFromPreviewRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String version,
        @Size(max = 1024) String description) {}
