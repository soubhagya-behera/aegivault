/**
 * Authentication DTOs, derived directly from the backend source.
 *
 * Backend (authoritative):
 *  - AuthResponse  = { token, tokenType, expiresIn, userId, email, roles[] }
 *      returned by POST /api/auth/login (200) and POST /api/auth/register (201).
 *  - UserProfile   = { userId, email, displayName (nullable), roles[] }
 *      returned by GET /api/auth/me (200). NOTE: this is NOT the same shape as
 *      AuthResponse — it has `displayName` and no token/expiresIn.
 *  - RegisterRequest = { email, password (8–72), displayName? }
 *  - LoginRequest    = { email, password }
 *
 * `roles` is typed as string[] on purpose: the backend returns a plain list of
 * role name strings. Do not narrow it to a closed union unless the backend
 * guarantees the set in the contract.
 */

/** Response from login and registration. Contains the bearer token. */
export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  userId: string;
  email: string;
  roles: string[];
}

/** Response from GET /api/auth/me. The current authenticated user's profile. */
export interface UserProfile {
  userId: string;
  email: string;
  displayName: string | null;
  roles: string[];
}

/** Payload for POST /api/auth/login. */
export interface LoginRequest {
  email: string;
  password: string;
}

/** Payload for POST /api/auth/register. `displayName` is optional. */
export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}
