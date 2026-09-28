package com.aegivault.aegivault.gateway.policy;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query layer over the audit ledger for one gateway usage policy.
 * Its single dependency is {@link AuditLedgerEntryRepository} — never the
 * policy repository, the policy service, the enforcement service, a counter,
 * Redis, the rate limiter, a provider, or a controller — so asking "what
 * happened to this policy" can never reach into enforcement or into current
 * policy state.
 *
 * <p><strong>Owner-scoped by construction.</strong> Every read requires an
 * {@code actorSubject} and filters on it in the same query as the resource
 * id, because the resource id comes from the URL. Scoping by resource id
 * alone would hand one actor the history of another actor's policy; there is
 * no unscoped variant, no ADMIN bypass, and no cross-user reporting.
 *
 * <p><strong>The ledger is the historical record, not the policy table.</strong>
 * No policy row is read, so the history of a deleted policy is still
 * returned, and a policy id that never existed simply has none. This layer
 * never reconstructs what a policy looked like: it returns the metadata
 * documents the policy audit events already wrote, verbatim, so no name,
 * description, limit, enabled state, owner, usage figure, counter, Redis
 * key, prompt, provider response, PII, or secret can be added here.
 *
 * <p>Only the five policy event types are returned — the three lifecycle
 * types and the two runtime enforcement types. Inspection events
 * ({@code AI_GATEWAY_INSPECTION_ALLOWED} / {@code _BLOCKED}) and every
 * unrelated event are excluded by the query itself, so they cannot leak
 * through a policy history.
 *
 * <p>Results are bounded to the newest {@value #MAX_ENTRIES} entries and
 * ordered newest-first ({@code createdAt} descending, then
 * {@code sequenceNumber} descending) with the bound applied in the database
 * query. There is no pagination, and being a query, this service is
 * strictly read-only: it never appends, updates, or deletes a ledger entry.
 * Cryptographic verification is untouched and remains a separate concern.
 */
@Service
@RequiredArgsConstructor
public class GatewayUsagePolicyAuditQueryService {

    /**
     * Maximum entries returned for one policy history: at most 100 newest
     * entries, enforced in the repository/database query — never by loading
     * a full history and trimming it in Java. No pagination exists yet.
     */
    public static final int MAX_ENTRIES = 100;

    /**
     * The closed vocabulary this history may contain: the three lifecycle
     * types and the two runtime enforcement types. Inspection and unrelated
     * events are deliberately absent.
     */
    private static final List<String> POLICY_EVENT_TYPES = List.of(
            AuditEventData.GATEWAY_USAGE_POLICY_CREATED,
            AuditEventData.GATEWAY_USAGE_POLICY_UPDATED,
            AuditEventData.GATEWAY_USAGE_POLICY_DELETED,
            AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED,
            AuditEventData.GATEWAY_USAGE_POLICY_REJECTED);

    private final AuditLedgerEntryRepository entries;

    /**
     * Returns one actor's audit history for one gateway usage policy,
     * newest first and bounded to {@value #MAX_ENTRIES} entries.
     *
     * <p>Works whether or not the policy row still exists: the ledger is the
     * record of what happened, so a deleted policy keeps its history and a
     * never-existing or foreign policy id simply returns nothing. That is
     * also what keeps this endpoint free of existence probing — an empty
     * result reveals nothing about whether someone else's policy is real.
     *
     * @param actorSubject authenticated actor, never blank; the same JWT
     *        subject the policy mutations are recorded under
     * @param policyId the policy whose history is read, never null
     * @return at most 100 of that actor's newest entries for that policy,
     *         possibly empty, never null, and never containing chain
     *         hashes, the actor subject, or any other internal field
     * @throws IllegalArgumentException when {@code actorSubject} is blank
     * @throws NullPointerException when {@code policyId} is null
     */
    @Transactional(readOnly = true)
    public List<GatewayUsagePolicyAuditEntry> historyFor(String actorSubject, UUID policyId) {
        Objects.requireNonNull(policyId, "policyId must not be null");
        return entries
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE,
                        policyId,
                        requireActor(actorSubject),
                        POLICY_EVENT_TYPES)
                .stream()
                .map(GatewayUsagePolicyAuditEntry::from)
                .toList();
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
