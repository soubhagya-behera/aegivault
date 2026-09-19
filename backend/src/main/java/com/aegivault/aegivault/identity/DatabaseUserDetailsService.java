package com.aegivault.aegivault.identity;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges Spring Security authentication to the {@code users} table. Emails
 * are normalized to lowercase so login matches registration regardless of
 * the casing typed by the caller.
 */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username.trim().toLowerCase(Locale.ROOT);
        return users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
