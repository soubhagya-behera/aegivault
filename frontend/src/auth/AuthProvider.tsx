import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useReducer,
  useRef,
} from "react";
import type { ReactNode } from "react";
import * as authApi from "../lib/api/auth";
import { authReducer, initialAuthState } from "./authReducer";
import type { AuthState } from "./authReducer";
import type { LoginRequest, RegisterRequest, UserProfile } from "../types/auth";

/**
 * Authentication provider.
 *
 * Security posture (see Phase 2B brief):
 *  - The bearer JWT is held ONLY in memory (a ref) and never written to
 *    localStorage, sessionStorage, IndexedDB, cookies, URLs, or logs.
 *  - Because there is no persistence, reloading the app returns to the signed
 *    -out state. GET /api/auth/me is NEVER called on initial load (there is no
 *    token to call it with); it is only called right after a successful
 *    login/register to load the full profile.
 *  - logout() clears the token and user state immediately.
 *  - Unauthorized (401) responses during login surface as a generic error and
 *    never authenticate the session.
 */

interface AuthContextValue extends AuthState {
  login: (credentials: LoginRequest, signal?: AbortSignal) => Promise<UserProfile>;
  register: (payload: RegisterRequest, signal?: AbortSignal) => Promise<UserProfile>;
  logout: () => void;
  /** Internal accessor for wiring authenticated requests. Never rendered. */
  getToken: () => string | null;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(authReducer, initialAuthState);
  const tokenRef = useRef<string | null>(null);

  const login = useCallback(
    async (credentials: LoginRequest, signal?: AbortSignal): Promise<UserProfile> => {
      const auth = await authApi.login(credentials, signal);
      const profile = await authApi.me(auth.token, signal);
      tokenRef.current = auth.token;
      dispatch({ type: "authenticated", user: profile });
      return profile;
    },
    [],
  );

  const register = useCallback(
    async (payload: RegisterRequest, signal?: AbortSignal): Promise<UserProfile> => {
      const auth = await authApi.register(payload, signal);
      const profile = await authApi.me(auth.token, signal);
      tokenRef.current = auth.token;
      dispatch({ type: "authenticated", user: profile });
      return profile;
    },
    [],
  );

  const logout = useCallback(() => {
    tokenRef.current = null;
    dispatch({ type: "loggedOut" });
  }, []);

  const getToken = useCallback(() => tokenRef.current, []);

  const value = useMemo<AuthContextValue>(
    () => ({ ...state, login, register, logout, getToken }),
    [state, login, register, logout, getToken],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === null) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
