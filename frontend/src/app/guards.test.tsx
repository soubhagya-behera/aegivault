import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { AuthStatus } from "../auth/authReducer";
import { RequireAuth, RedirectIfAuthed } from "./guards";

// The auth status is the only thing the guards read; mock the hook so the
// guard logic is tested in isolation from the real provider.
const auth = vi.hoisted(() => ({ status: "unauthenticated" as AuthStatus }));
vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => auth,
}));

function renderAt(path: string, state?: unknown) {
  const entry = state === undefined ? path : { pathname: path, state };
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route element={<RequireAuth />}>
          <Route path="/dashboard" element={<div>workspace</div>} />
        </Route>
        <Route element={<RedirectIfAuthed />}>
          <Route path="/login" element={<div>login</div>} />
          <Route path="/register" element={<div>register</div>} />
        </Route>
        <Route path="/" element={<div>landing</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("route guards", () => {
  it("RequireAuth redirects an unauthenticated user away from the dashboard", () => {
    auth.status = "unauthenticated";
    renderAt("/dashboard");
    expect(screen.queryByText("workspace")).toBeNull();
    expect(screen.getByText("login")).toBeTruthy();
  });

  it("RequireAuth admits an authenticated user to the dashboard", () => {
    auth.status = "authenticated";
    renderAt("/dashboard");
    expect(screen.getByText("workspace")).toBeTruthy();
  });

  it("RedirectIfAuthed sends an authenticated user from login to the dashboard", () => {
    auth.status = "authenticated";
    renderAt("/login");
    expect(screen.queryByText("login")).toBeNull();
    expect(screen.getByText("workspace")).toBeTruthy();
  });

  it("RedirectIfAuthed returns an authenticated user to their saved destination", () => {
    auth.status = "authenticated";
    renderAt("/login", { from: "/dashboard" });
    expect(screen.getByText("workspace")).toBeTruthy();
  });

  it("RedirectIfAuthed ignores an unsafe saved destination", () => {
    auth.status = "authenticated";
    renderAt("/login", { from: "https://evil.example/" });
    expect(screen.getByText("workspace")).toBeTruthy();
  });

  it("RedirectIfAuthed shows the login page to an unauthenticated user", () => {
    auth.status = "unauthenticated";
    renderAt("/login");
    expect(screen.getByText("login")).toBeTruthy();
  });

  it("RedirectIfAuthed shows the register page to an unauthenticated user", () => {
    auth.status = "unauthenticated";
    renderAt("/register");
    expect(screen.getByText("register")).toBeTruthy();
  });
});
