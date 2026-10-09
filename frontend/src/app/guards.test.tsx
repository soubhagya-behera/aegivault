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

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<div>workspace</div>} />
        </Route>
        <Route element={<RedirectIfAuthed />}>
          <Route path="/login" element={<div>login</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("route guards", () => {
  it("RequireAuth redirects an unauthenticated user away from the workspace", () => {
    auth.status = "unauthenticated";
    renderAt("/");
    expect(screen.queryByText("workspace")).toBeNull();
    expect(screen.getByText("login")).toBeTruthy();
  });

  it("RequireAuth admits an authenticated user to the workspace", () => {
    auth.status = "authenticated";
    renderAt("/");
    expect(screen.getByText("workspace")).toBeTruthy();
  });

  it("RedirectIfAuthed sends an authenticated user away from the login page", () => {
    auth.status = "authenticated";
    renderAt("/login");
    expect(screen.queryByText("login")).toBeNull();
    expect(screen.getByText("workspace")).toBeTruthy();
  });

  it("RedirectIfAuthed shows the login page to an unauthenticated user", () => {
    auth.status = "unauthenticated";
    renderAt("/login");
    expect(screen.getByText("login")).toBeTruthy();
  });
});
