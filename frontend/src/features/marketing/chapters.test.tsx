import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import LandingPage from "./LandingPage";

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/home"]}>
      <LandingPage />
    </MemoryRouter>,
  );
}

describe("landing chapters (Phase 3D)", () => {
  it("renders five deliberate visual chapters with scoped scopes", () => {
    renderPage();
    for (const chapter of ["opening", "platform", "workflow", "evidence", "convert"]) {
      expect(document.querySelector(`[data-mkt-chapter="${chapter}"]`)).not.toBeNull();
    }
  });

  it("keeps the sticky workflow narrative to five stages with a schematic", () => {
    renderPage();
    const workflow = document.getElementById("workflow") as HTMLElement;
    expect(workflow.getAttribute("data-mkt-chapter")).toBe("workflow");
    expect(within(workflow).getAllByRole("listitem")).toHaveLength(5);
    // Phase 3G renders the schematic twice (desktop pinned visual + mobile
    // folding card; CSS shows one per viewport, jsdom shows both) — the
    // narrative still owns at least one schematic.
    expect(within(workflow).getAllByText(/workflow schematic/i).length).toBeGreaterThan(0);
    for (const name of ["Source", "Inspect", "Sanitize", "Govern", "Verify"]) {
      expect(within(workflow).getAllByText(name).length).toBeGreaterThan(0);
    }
  });

  it("closes with a conversion chapter linking to sign-in", () => {
    renderPage();
    const signIn = screen.getByRole("link", { name: "Sign in to Aegivault" });
    expect(signIn.getAttribute("href")).toBe("/login");
    expect(screen.getByRole("heading", { name: /bring production data under control/i })).toBeTruthy();
  });
});
