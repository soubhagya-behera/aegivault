package com.aegivault.aegivault.gateway.usage;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link GatewayUsageRecord}. Standard CRUD comes
 * from {@link JpaRepository}; usage adds exactly one access path beyond
 * the primary key: lookup by the server-generated gateway request id
 * (UNIQUE, so at most one row). Actor history reads use the dedicated V9
 * {@code (actor_subject, created_at)} index through derived queries added
 * only when a real reader needs them — there is no read endpoint yet.
 */
public interface GatewayUsageRepository extends JpaRepository<GatewayUsageRecord, UUID> {

    Optional<GatewayUsageRecord> findByRequestId(UUID requestId);
}
