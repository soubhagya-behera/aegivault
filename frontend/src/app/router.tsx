import { createBrowserRouter, Navigate } from "react-router-dom";
import type { RouteObject } from "react-router-dom";
import { RedirectIfAuthed, RequireAuth } from "./guards";
import PublicLayout from "../layouts/PublicLayout";
import AppLayout from "../layouts/AppLayout";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import LandingPage from "../features/marketing/LandingPage";
import ScaffoldStatus from "../features/scaffold/ScaffoldStatus";

/**
 * Application router.
 *
 *  - /                  → public marketing landing page (PublicLayout +
 *                         landing hero). Public: no guard, no auth changes.
 *  - /home              → redirect to / (legacy entry point).
 *  - /login, /register  → public, guarded by RedirectIfAuthed
 *  - /dashboard         → protected workspace, guarded by RequireAuth.
 *                         Temporarily renders the honest scaffold placeholder
 *                         inside AppLayout; real workspace routes come later.
 *  - *                  → redirect to /
 *
 * The protected workspace moved from / to /dashboard so the landing page
 * owns the root; auth redirects target /dashboard (see guards).
 */
export const appRoutes: RouteObject[] = [
  {
    element: <PublicLayout />,
    children: [
      { path: "/", element: <LandingPage /> },
      { path: "/home", element: <Navigate to="/" replace /> },
    ],
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
        path: "/dashboard",
        element: <AppLayout />,
        children: [{ index: true, element: <ScaffoldStatus /> }],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
];

export const router = createBrowserRouter(appRoutes);
