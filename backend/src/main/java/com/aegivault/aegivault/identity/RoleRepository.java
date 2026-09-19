package com.aegivault.aegivault.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link Role}. Only lookup needed is by name, used
 * when assigning the seeded {@code USER} role at registration.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);
}
