import { createBrowserRouter, Navigate } from "react-router-dom";
import { RedirectIfAuthed, RequireAuth } from "./guards";
import PublicLayout from "../layouts/PublicLayout";
import AppLayout from "../layouts/AppLayout";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import LandingPage from "../features/marketing/LandingPage";
import ScaffoldStatus from "../features/scaffold/ScaffoldStatus";

/**
 * Application router (Phase 2B; extended in Phase 3A).
 *
 *  - /home              → public marketing landing page (PublicLayout +
 *                         landing hero). Public: no guard, no auth changes.
 *  - /login, /register  → public, guarded by RedirectIfAuthed
 *  - /                  → protected workspace, guarded by RequireAuth.
 *                         Temporarily renders the honest scaffold placeholder
 *                         inside AppLayout; real workspace routes come later.
 *  - *                  → redirect to /
 *
 * The public marketing landing page will replace the root redirect behavior in
 * a later phase (until then it is mounted at /home, so the protected root and
 * every auth redirect keep their current behavior). Feature routes are added
 * without changing this structure.
 */
export const router = createBrowserRouter([
  {
    element: <PublicLayout />,
    children: [{ path: "/home", element: <LandingPage /> }],
  },
  {
    element: <RedirectIfAuthed />,
    children: [
      { path: "/login", element: <LoginPage /> },
      { path: "/register", element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        path: "/",
        element: <AppLayout />,
        children: [{ index: true, element: <ScaffoldStatus /> }],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
]);
