import type { UserProfile } from "../types/auth";

/**
 * Pure authentication state machine.
 *
 * Deliberately tiny and framework-free so it can be unit-tested without React.
 * The token is intentionally NOT part of this state: it lives in a ref inside
 * the provider (never rendered, never persisted) and is exposed only through a
 * `getToken()` accessor for request wiring.
 */

export type AuthStatus = "authenticated" | "unauthenticated";

export interface AuthState {
  user: UserProfile | null;
  status: AuthStatus;
}

export type AuthAction =
  | { type: "authenticated"; user: UserProfile }
  | { type: "loggedOut" };

export const initialAuthState: AuthState = {
  user: null,
  status: "unauthenticated",
};

export function authReducer(state: AuthState, action: AuthAction): AuthState {
  switch (action.type) {
    case "authenticated":
      return { user: action.user, status: "authenticated" };
    case "loggedOut":
      return initialAuthState;
    default:
      return state;
  }
}
