package com.aegivault.aegivault.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. The password upper bound (72) is the BCrypt input
 * limit, not an arbitrary choice; the minimum (8) is the accepted floor.
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        String displayName) {}
