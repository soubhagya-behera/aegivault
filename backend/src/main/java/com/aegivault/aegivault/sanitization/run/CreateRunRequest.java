package com.aegivault.aegivault.sanitization.run;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Creation payload for {@code POST /api/runs}: a dataset reference plus a
 * persisted-policy reference. The run executes the referenced policy's
 * rules against the referenced dataset's stored input; the owner comes from
 * the verified JWT subject and must own <em>both</em> resources.
 *
 * <p>Both ids are server-resolved: labels and rules are read from the
 * persisted policy at creation time and frozen into the run's immutable
 * {@code PolicySnapshot}, so this payload — like every other creation
 * payload — carries references only, never policy content.
 *
 * <p>Expected JSON shape:
 *
 * <pre>
 * {
 *   "datasetId": "...",
 *   "policyId": "..."
 * }
 * </pre>
 *
 * @param datasetId dataset to sanitize, never null
 * @param policyId persisted policy to execute, never null
 */
public record CreateRunRequest(@NotNull UUID datasetId, @NotNull UUID policyId) {}
