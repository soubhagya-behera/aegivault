import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";

/**
 * Route guards. A guard is the ONLY thing that decides access — hiding a UI
 * element is never treated as authorization. These read the real auth status
 * from the provider.
 */

/** Allows only authenticated users into the protected workspace. */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === "unauthenticated") {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}

/** Keeps already-authenticated users out of the login/register pages. */
export function RedirectIfAuthed() {
  const { status } = useAuth();

  if (status === "authenticated") {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}
