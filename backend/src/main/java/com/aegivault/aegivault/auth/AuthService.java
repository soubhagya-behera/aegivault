package com.aegivault.aegivault.auth;

import com.aegivault.aegivault.identity.Role;
import com.aegivault.aegivault.identity.RoleRepository;
import com.aegivault.aegivault.identity.User;
import com.aegivault.aegivault.identity.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password registration and login. Registration normalizes the email,
 * rejects duplicates, BCrypt-hashes the password, assigns the seeded
 * {@code USER} role, and returns a signed token. Login authenticates
 * through the Spring Security {@link AuthenticationManager} (DAO provider
 * backed by {@code DatabaseUserDetailsService} plus BCrypt verification)
 * so unknown emails and wrong passwords both fail as {@code 401} with an
 * identical message.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(String email, String password, String displayName) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(normalized).isPresent()) {
            throw new EmailAlreadyExistsException(normalized);
        }
        Role userRole = roles.findByName("USER")
                .orElseThrow(() -> new IllegalStateException("USER role is not seeded"));
        User user = new User(normalized, passwordEncoder.encode(password));
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName.trim());
        }
        user.getRoles().add(userRole);
        users.save(user);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalized, password));
        return toResponse((User) authentication.getPrincipal());
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(
                jwtService.issueToken(user),
                "Bearer",
                jwtService.ttlSeconds(),
                user.getId(),
                user.getEmail(),
                user.getRoles().stream().map(Role::getName).sorted().toList());
    }
}
