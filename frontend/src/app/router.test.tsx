import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router-dom";
import type { AuthStatus } from "../auth/authReducer";
import { appRoutes } from "./router";

// Auth status is the only thing routing decisions read; mock the hook so the
// full route table is exercised without a live backend.
const auth = vi.hoisted(() => ({ status: "unauthenticated" as AuthStatus }));
vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => auth,
}));

function renderAt(path: string) {
  const testRouter = createMemoryRouter(appRoutes, { initialEntries: [path] });
  return render(<RouterProvider router={testRouter} />);
}

describe("application routes", () => {
  it("renders the public landing page at / without authentication", () => {
    auth.status = "unauthenticated";
    renderAt("/");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Production data, without the exposure.",
    );
  });

  it("keeps the landing page public when authenticated", () => {
    auth.status = "authenticated";
    renderAt("/");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Production data, without the exposure.",
    );
  });

  it("redirects /home to / without a loop", () => {
    auth.status = "unauthenticated";
    renderAt("/home");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Production data, without the exposure.",
    );
  });

  it("renders the sign-in page at /login", () => {
    auth.status = "unauthenticated";
    renderAt("/login");
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeTruthy();
  });

  it("renders the registration page at /register", () => {
    auth.status = "unauthenticated";
    renderAt("/register");
    expect(screen.getByRole("heading", { name: "Create your account" })).toBeTruthy();
  });

  it("protects /dashboard for unauthenticated users", () => {
    auth.status = "unauthenticated";
    renderAt("/dashboard");
    expect(screen.queryByText("You're signed in.")).toBeNull();
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeTruthy();
  });

  it("admits authenticated users to /dashboard", () => {
    auth.status = "authenticated";
    renderAt("/dashboard");
    expect(screen.getByText("You're signed in.")).toBeTruthy();
  });

  it("sends authenticated users from /login to the dashboard", () => {
    auth.status = "authenticated";
    renderAt("/login");
    expect(screen.queryByRole("heading", { name: "Welcome back" })).toBeNull();
    expect(screen.getByText("You're signed in.")).toBeTruthy();
  });

  it("sends authenticated users from /register to the dashboard", () => {
    auth.status = "authenticated";
    renderAt("/register");
    expect(screen.queryByRole("heading", { name: "Create your account" })).toBeNull();
    expect(screen.getByText("You're signed in.")).toBeTruthy();
  });

  it("redirects unknown routes to the public landing page", () => {
    auth.status = "unauthenticated";
    renderAt("/no-such-route");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Production data, without the exposure.",
    );
  });
});
