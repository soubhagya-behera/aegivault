package com.aegivault.aegivault.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves the V2 persistence path against real PostgreSQL: the migration
 * applies, {@code USER}/{@code ADMIN} are seeded, users round-trip with
 * their roles, passwords persist only as BCrypt hashes, and roles map to
 * {@code ROLE_<name>} authorities.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserRepositoryTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Test
    void seedsUserAndAdminRoles() {
        List<String> names = roles.findAll().stream().map(Role::getName).sorted().toList();
        assertThat(names).containsExactly("ADMIN", "USER");
    }

    @Test
    void persistAndRetrieveUserWithRole() {
        Role userRole = roles.findByName("USER").orElseThrow();
        User user = new User("auth-case@example.com", passwordEncoder.encode("correct-horse-1"));
        user.setDisplayName("Auth Case");
        user.getRoles().add(userRole);

        User saved = users.saveAndFlush(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.isEnabled()).isTrue();

        User found = users.findByEmail("auth-case@example.com").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getDisplayName()).isEqualTo("Auth Case");
        assertThat(found.getRoles()).extracting(Role::getName).containsExactly("USER");
    }

    @Test
    void passwordIsStoredAsBcryptHashNeverPlaintext() {
        String plaintext = "s3cret-pass-9";
        User user = new User("hash-case@example.com", passwordEncoder.encode(plaintext));
        user.getRoles().add(roles.findByName("USER").orElseThrow());
        users.saveAndFlush(user);

        User found = users.findByEmail("hash-case@example.com").orElseThrow();
        assertThat(found.getPassword()).isNotEqualTo(plaintext);
        assertThat(found.getPassword()).startsWith("$2a$12$");
        assertThat(passwordEncoder.matches(plaintext, found.getPassword())).isTrue();
    }

    @Test
    void rolesMapToRolePrefixedAuthorities() {
        User user = new User("roles-case@example.com", passwordEncoder.encode("s3cret-pass-9"));
        user.getRoles().add(roles.findByName("USER").orElseThrow());
        user.getRoles().add(roles.findByName("ADMIN").orElseThrow());

        assertThat(user.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }
}
