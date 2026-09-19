package com.aegivault.aegivault.auth;

import jakarta.validation.constraints.NotBlank;

/** Login payload. Deliberately loose validation: credential problems of any
 * kind surface as a generic 401, never revealing whether the email exists. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
