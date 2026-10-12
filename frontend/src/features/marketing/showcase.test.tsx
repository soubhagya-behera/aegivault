import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import LandingPage from "./LandingPage";

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/home"]}>
      <LandingPage />
    </MemoryRouter>,
  );
}

describe("landing sections (Phase 3B)", () => {
  it("provides real targets for all three navigation anchors", () => {
    renderPage();

    for (const id of ["platform", "workflow", "security"]) {
      const target = document.getElementById(id);
      expect(target).not.toBeNull();
      expect(target?.tagName).toBe("SECTION");
    }
    expect(document.getElementById("platform-title")).not.toBeNull();
    expect(document.getElementById("workflow-title")).not.toBeNull();
    expect(document.getElementById("security-title")).not.toBeNull();
  });

  it("lists all six capabilities with the first selected by default", () => {
    renderPage();

    const tabs = screen.getAllByRole("tab");
    expect(tabs).toHaveLength(6);
    expect(tabs[0]?.getAttribute("aria-selected")).toBe("true");
    expect(tabs[0]?.tabIndex).toBe(0);
    expect(tabs[1]?.tabIndex).toBe(-1);

    const panel = screen.getByRole("tabpanel");
    expect(screen.getByRole("heading", { level: 3, name: /profiling & pii detection/i })).toBeTruthy();
    expect(panel.textContent).toContain("Whole-value detectors");
    expect(panel.textContent).toContain("Schematic");
  });

  it("changes the detail panel and active state when a capability is selected", () => {
    renderPage();

    const gatewayTab = screen.getByRole("tab", { name: /request and response inspection/i });
    fireEvent.click(gatewayTab);

    expect(gatewayTab.getAttribute("aria-selected")).toBe("true");
    expect(gatewayTab.tabIndex).toBe(0);
    expect(screen.getAllByRole("tab")[0]?.getAttribute("aria-selected")).toBe("false");

    const panel = screen.getByRole("tabpanel");
    expect(panel.getAttribute("aria-labelledby")).toBe("platform-tab-gateway");
    expect(screen.getByRole("heading", { level: 3, name: /gateway request and response inspection/i })).toBeTruthy();
    expect(panel.textContent).toContain("reason codes");
    expect(screen.queryByText(/whole-value detectors/i)).toBeNull();

    const auditTab = screen.getByRole("tab", { name: /cryptographically linked audit evidence/i });
    fireEvent.click(auditTab);
    expect(screen.getByRole("tabpanel").textContent).toContain("not a blockchain");
    expect(screen.queryByText(/provider payload is released/i)).toBeNull();
  });

  it("supports arrow and Home/End keyboard navigation on the tablist", () => {
    renderPage();

    const tablist = screen.getByRole("tablist", { name: "Platform capabilities" });
    const tabs = screen.getAllByRole("tab");
    tabs[0]?.focus();

    fireEvent.keyDown(tablist, { key: "ArrowDown" });
    expect(tabs[1]?.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(tabs[1]);

    fireEvent.keyDown(tablist, { key: "End" });
    expect(tabs[5]?.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(tabs[5]);

    fireEvent.keyDown(tablist, { key: "Home" });
    expect(tabs[0]?.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(tabs[0]);
  });

  it("explains the five workflow stages without a separate distinctions glossary", () => {
    renderPage();

    const workflow = within(document.getElementById("workflow") as HTMLElement);
    expect(workflow.getAllByRole("listitem")).toHaveLength(5);
    for (const name of ["Source", "Inspect", "Sanitize", "Govern", "Verify"]) {
      expect(workflow.getAllByText(name).length).toBeGreaterThan(0);
    }
    // Main stage descriptions are preserved in the workflow itself
    // (desktop list and mobile panels render the same copy).
    expect(workflow.getAllByText(/the source is never written to/i).length).toBeGreaterThan(0);
    expect(workflow.getAllByText(/the first broken link is reported/i).length).toBeGreaterThan(0);
    // The redundant glossary is gone entirely.
    expect(workflow.queryByText(/where the stages differ/i)).toBeNull();
    expect(workflow.queryByText(/never changes a value and never writes a policy/i)).toBeNull();
    expect(workflow.queryByText(/before any provider is called/i)).toBeNull();
  });

  it("states the four security boundaries with honest caveats", () => {
    renderPage();

    const security = within(document.getElementById("security") as HTMLElement);
    expect(security.getByText("Owner-scoped resources")).toBeTruthy();
    expect(security.getByText(/do not claim to catch every possible secret/i)).toBeTruthy();
    expect(security.getByText(/not tamper-proofing, and not a blockchain/i)).toBeTruthy();
    expect(security.getByText(/claims no external compliance certification/i)).toBeTruthy();
  });
});
