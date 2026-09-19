package com.aegivault.aegivault.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Closed RBAC role set ({@code USER}, {@code ADMIN}), enforced by the
 * {@code chk_roles_name} database check and seeded by the V2 migration.
 * Maps to a {@code ROLE_<name>} granted authority on {@link User}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Setter
    @Column(name = "name", nullable = false, unique = true)
    private String name;

    public Role(String name) {
        this.name = name;
    }
}
