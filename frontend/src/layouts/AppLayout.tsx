import { Outlet, useNavigate } from "react-router-dom";
import { LogOut } from "lucide-react";
import { useAuth } from "../auth/AuthProvider";

/**
 * Minimal application shell (Phase 2B).
 *
 * This is intentionally NOT the finished dashboard shell, sidebar, or top bar —
 * those are built in later phases. It provides just enough frame to host the
 * routed workspace and demonstrate the authenticated session (identity label +
 * sign out). The routed child renders via <Outlet />.
 */
export default function AppLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  function handleSignOut() {
    logout();
    navigate("/login", { replace: true });
  }

  const identity = user?.displayName ?? user?.email ?? "";

  return (
    <div className="min-h-dvh bg-canvas text-text">
      <header className="border-b border-border">
        <div className="mx-auto flex max-w-app items-center justify-between gap-4 px-6 py-3">
          <span className="font-display text-sm font-semibold tracking-tight text-text">
            Aegivault
          </span>
          <div className="flex items-center gap-4">
            <span className="hidden font-mono text-xs text-text-muted sm:inline">
              {identity}
            </span>
            <button
              type="button"
              onClick={handleSignOut}
              className="inline-flex items-center gap-2 rounded-md border border-border px-3 py-1.5 text-sm text-text-muted transition-colors hover:border-border-strong hover:bg-surface-raised hover:text-text"
            >
              <LogOut aria-hidden="true" className="size-4" />
              Sign out
            </button>
          </div>
        </div>
      </header>
      <Outlet />
    </div>
  );
}
