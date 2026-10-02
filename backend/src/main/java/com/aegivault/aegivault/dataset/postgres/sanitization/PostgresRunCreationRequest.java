package com.aegivault.aegivault.dataset.postgres.sanitization;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Creation payload for {@code POST /api/datasets/{datasetId}/postgres/runs}.
 *
 * <p>The dataset is <em>not</em> in the body: it is the path variable, so the
 * request cannot name a dataset that differs from the one being bound into the
 * URL. The only field is a reference to one persisted policy, and that policy's
 * labels and rules are read server-side and frozen into the run's immutable
 * {@code PolicySnapshot} — so this payload, like every other creation payload
 * in this repository, carries a reference and never policy content.
 *
 * <p><strong>Everything else is absent by construction.</strong> There is no
 * field for an owner, a schema, a table, a source type, a connection detail, a
 * credential, a JDBC URL, a transformation rule, a SQL fragment, a requested
 * token value, or a row limit, so none of those can be supplied and none can
 * reach the run. A caller that sends them anyway is ignored, which is the
 * project's existing unknown-property behaviour.
 *
 * <p>Expected JSON shape:
 *
 * <pre>
 * {
 *   "policyId": "..."
 * }
 * </pre>
 *
 * @param policyId persisted policy to freeze into the run, never null
 */
public record PostgresRunCreationRequest(@NotNull UUID policyId) {}