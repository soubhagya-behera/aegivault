import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import LandingPage from "./LandingPage";
import { nearestCenterIndex } from "./motion";

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/home"]}>
      <LandingPage />
    </MemoryRouter>,
  );
}

describe("workflow stage selection (Chapter 03 sync fix)", () => {
  it("selects the panel closest to the viewport center while scrolling forward", () => {
    // Five panel centers evenly spaced; the viewport center moves down.
    const centers = [100, 300, 500, 700, 900];
    expect(nearestCenterIndex(centers, 100)).toBe(0);
    expect(nearestCenterIndex(centers, 199)).toBe(0);
    expect(nearestCenterIndex(centers, 201)).toBe(1);
    expect(nearestCenterIndex(centers, 500)).toBe(2);
    expect(nearestCenterIndex(centers, 650)).toBe(3);
  });

  it("reaches Verify at the bottom and returns symmetrically when scrolling back", () => {
    const centers = [100, 300, 500, 700, 900];
    expect(nearestCenterIndex(centers, 900)).toBe(4);
    expect(nearestCenterIndex(centers, 1200)).toBe(4);
    expect(nearestCenterIndex(centers, 700)).toBe(3);
    expect(nearestCenterIndex(centers, 300)).toBe(1);
    expect(nearestCenterIndex(centers, -200)).toBe(0);
  });

  it("resolves ties to the earlier stage and handles edge input", () => {
    expect(nearestCenterIndex([100, 300], 200)).toBe(0);
    expect(nearestCenterIndex([], 200)).toBe(0);
    expect(nearestCenterIndex([420], 900)).toBe(0);
  });
});

describe("workflow chapter composition (Chapter 03 sync fix)", () => {
  it("renders five stages with a schematic and no distinctions glossary", () => {
    renderPage();
    const workflow = document.getElementById("workflow") as HTMLElement;
    expect(workflow.getAttribute("data-mkt-chapter")).toBe("workflow");
    // The desktop ordered list remains the single list landmark (mobile
    // panels are plain blocks so the five-stage contract is unchanged).
    expect(within(workflow).getAllByRole("listitem")).toHaveLength(5);
    expect(within(workflow).getAllByText(/workflow schematic/i).length).toBeGreaterThan(0);
    for (const name of ["Source", "Inspect", "Sanitize", "Govern", "Verify"]) {
      expect(within(workflow).getAllByText(name).length).toBeGreaterThan(0);
    }
    expect(
      within(workflow).queryByRole("heading", { name: /where the stages differ/i }),
    ).toBeNull();
    expect(screen.queryByText(/where the stages differ/i)).toBeNull();
  });

  it("keeps one workflow stage rail for navigation", () => {
    renderPage();
    const workflow = document.getElementById("workflow") as HTMLElement;
    const rail = within(workflow).getByRole("group", { name: "Workflow stages" });
    expect(within(rail).getAllByRole("button")).toHaveLength(5);
  });
});
