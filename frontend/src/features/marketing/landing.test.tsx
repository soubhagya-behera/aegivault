import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { AuthStatus } from "../../auth/authReducer";
import PublicLayout from "../../layouts/PublicLayout";
import LandingPage from "./LandingPage";

// Auth status is the only thing the navigation reads from the provider; mock
// the hook (same pattern as guards.test.tsx) so both CTA variants are testable
// without a live backend.
const auth = vi.hoisted(() => ({ status: "unauthenticated" as AuthStatus }));
vi.mock("../../auth/AuthProvider", () => ({
  useAuth: () => auth,
}));

function renderLanding() {
  return render(
    <MemoryRouter initialEntries={["/home"]}>
      <Routes>
        <Route element={<PublicLayout />}>
          <Route path="/home" element={<LandingPage />} />
        </Route>
        <Route path="/login" element={<div>login route</div>} />
        <Route path="/" element={<div>workspace route</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("landing page (Phase 3A)", () => {
  it("renders the exact hero messaging with real destinations", () => {
    auth.status = "unauthenticated";
    renderLanding();

    // In jsdom the Tailwind stylesheet is absent, so the line spans compute
    // as inline and the accname library joins them without a separator; the
    // regex tolerates that while still asserting the full headline.
    expect(
      screen.getByRole("heading", {
        level: 1,
        name: /production data,\s*without the exposure\./i,
      }),
    ).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Production data, without the exposure.",
    );
    expect(screen.getByText(/data privacy \/ security \/ ai governance/i)).toBeTruthy();
    expect(
      screen.getByText(
        /inspect sensitive data, apply controlled transformations, and govern ai-bound requests/i,
      ),
    ).toBeTruthy();

    const primary = screen.getByRole("link", { name: "Explore the platform" });
    expect(primary.getAttribute("href")).toBe("#platform");

    const secondary = screen.getByRole("link", { name: "Open workspace" });
    expect(secondary.getAttribute("href")).toBe("/");
  });

  it("shows the pipeline stages and the fictional-data disclaimer", () => {
    auth.status = "unauthenticated";
    renderLanding();

    for (const stage of ["Source", "Inspect", "Sanitize", "Govern", "Verify"]) {
      expect(screen.getByText(stage)).toBeTruthy();
    }
    expect(screen.getByText(/example values are fictional/i)).toBeTruthy();
    expect(screen.getByText("email → tok_av_7f2c")).toBeTruthy();
  });

  it("links section anchors and the sign-in route when signed out", () => {
    auth.status = "unauthenticated";
    renderLanding();

    const sections = [
      { href: "#platform", label: "Platform" },
      { href: "#workflow", label: "Workflow" },
      { href: "#security", label: "Security" },
    ];
    for (const section of sections) {
      const links = screen.getAllByRole("link", { name: section.label });
      expect(links.length).toBeGreaterThan(0);
      links.forEach((link) => expect(link.getAttribute("href")).toBe(section.href));
    }

    const signIn = screen.getByRole("link", { name: "Sign in" });
    expect(signIn.getAttribute("href")).toBe("/login");
  });

  it("uses the protected workspace route for the action when authenticated", () => {
    auth.status = "authenticated";
    renderLanding();

    const workspaceLinks = screen.getAllByRole("link", { name: "Open workspace" });
    expect(workspaceLinks.length).toBeGreaterThan(0);
    workspaceLinks.forEach((link) => expect(link.getAttribute("href")).toBe("/"));
    expect(screen.queryByRole("link", { name: "Sign in" })).toBeNull();
  });

  it("navigates the secondary CTA to the workspace route", () => {
    auth.status = "unauthenticated";
    renderLanding();

    fireEvent.click(screen.getByRole("link", { name: "Open workspace" }));
    expect(screen.getByText("workspace route")).toBeTruthy();
  });

  it("opens the mobile menu, moves focus in, and closes with Escape", () => {
    auth.status = "unauthenticated";
    renderLanding();

    const toggle = screen.getByRole("button", { name: "Open navigation menu" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getAllByRole("link", { name: "Platform" }).length).toBe(2);
    expect(document.activeElement?.textContent).toBe("Platform");

    fireEvent.keyDown(document, { key: "Escape" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(document.activeElement).toBe(toggle);
  });

  it("exposes the landmarks and skip link", () => {
    auth.status = "unauthenticated";
    renderLanding();

    expect(screen.getByRole("main")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Sections" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Skip to main content" })).toBeTruthy();
    expect(screen.getByRole("link", { name: /aegivault/i }).getAttribute("href")).toBe(
      "/home",
    );
  });
});
