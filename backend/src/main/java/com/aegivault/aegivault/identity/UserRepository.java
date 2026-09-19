package com.aegivault.aegivault.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link User}. Standard CRUD comes from
 * {@link JpaRepository}; {@code findByEmail} serves authentication (email is
 * the unique login identifier backed by {@code uq_users_email}).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}
