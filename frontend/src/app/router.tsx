import { createBrowserRouter, Navigate } from "react-router-dom";
import { RedirectIfAuthed, RequireAuth } from "./guards";
import AppLayout from "../layouts/AppLayout";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import ScaffoldStatus from "../features/scaffold/ScaffoldStatus";

/**
 * Application router (Phase 2B).
 *
 *  - /login, /register  → public, guarded by RedirectIfAuthed
 *  - /                  → protected workspace, guarded by RequireAuth.
 *                         Temporarily renders the honest scaffold placeholder
 *                         inside AppLayout; real workspace routes come later.
 *  - *                  → redirect to /
 *
 * The public marketing landing page will replace the root redirect behavior in
 * a later phase. Feature routes are added without changing this structure.
 */
export const router = createBrowserRouter([
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
