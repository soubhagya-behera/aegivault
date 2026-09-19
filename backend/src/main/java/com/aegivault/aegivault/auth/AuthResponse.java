package com.aegivault.aegivault.auth;

import java.util.List;
import java.util.UUID;

/** Authentication result returned by registration, login, and profile lookup. */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email,
        List<String> roles) {}
