package com.aegivault.aegivault.auth;

import java.util.List;
import java.util.UUID;

/** Authenticated caller profile served by {@code GET /api/auth/me}. */
public record UserProfile(UUID userId, String email, String displayName, List<String> roles) {}
