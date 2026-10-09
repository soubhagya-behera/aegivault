import { request } from "./client";
import type {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserProfile,
} from "../../types/auth";

/**
 * Authentication API. Transport-only: no React, no storage, no UI concerns.
 * Paths are base-relative (the "/api" prefix is added by the shared client).
 */

/** POST /api/auth/login → 200 AuthResponse (contains the bearer token). */
export function login(
  credentials: LoginRequest,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return request<AuthResponse>("/auth/login", {
    method: "POST",
    body: credentials,
    signal,
  });
}

/** POST /api/auth/register → 201 AuthResponse (contains the bearer token). */
export function register(
  payload: RegisterRequest,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return request<AuthResponse>("/auth/register", {
    method: "POST",
    body: payload,
    signal,
  });
}

/**
 * GET /api/auth/me → 200 UserProfile. Requires a valid bearer token; only call
 * this when an in-memory token exists. The response is a DIFFERENT shape from
 * the login/register AuthResponse (it carries displayName, not the token).
 */
export function me(token: string, signal?: AbortSignal): Promise<UserProfile> {
  return request<UserProfile>("/auth/me", {
    method: "GET",
    token,
    signal,
  });
}
