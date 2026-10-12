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

describe("landing motion polish (Phase 3C)", () => {
  it("resolves rapid selection to the latest panel with no stale content", () => {
    renderPage();

    // Rapid clicks: gateway → limits → audit. mode="wait" must never overlap
    // panels or strand the intermediate copy.
    fireEvent.click(screen.getByRole("tab", { name: /request and response inspection/i }));
    fireEvent.click(screen.getByRole("tab", { name: /limits, usage policies, and token budgets/i }));
    fireEvent.click(screen.getByRole("tab", { name: /cryptographically linked audit evidence/i }));

    expect(screen.getAllByRole("tabpanel")).toHaveLength(1);
    const panel = screen.getByRole("tabpanel");
    expect(panel.getAttribute("aria-labelledby")).toBe("platform-tab-audit");
    expect(panel.textContent).toContain("not a blockchain");
    expect(panel.textContent).not.toContain("provider payload is released");
    expect(panel.textContent).not.toContain("reservation and settlement");

    const tabs = screen.getAllByRole("tab");
    expect(tabs.filter((tab) => tab.getAttribute("aria-selected") === "true")).toHaveLength(1);
  });

  it("keeps a visible active indicator and keyboard focus under rapid arrow keys", () => {
    renderPage();

    const tablist = screen.getByRole("tablist", { name: "Platform capabilities" });
    const tabs = screen.getAllByRole("tab");
    tabs[0]?.focus();

    fireEvent.keyDown(tablist, { key: "ArrowDown" });
    fireEvent.keyDown(tablist, { key: "ArrowDown" });

    const selected = screen.getAllByRole("tab")[2];
    expect(selected?.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(selected);
    expect(screen.getByRole("tabpanel").getAttribute("aria-labelledby")).toBe(
      "platform-tab-postgres",
    );
    // The shared-layout oxide rule renders on exactly the selected tab, even
    // with motion disabled (static jump instead of a glide).
    expect(selected?.querySelector(".bg-accent")).not.toBeNull();
    for (const tab of tabs.filter((tab) => tab !== selected)) {
      expect(tab.querySelector(".bg-accent")).toBeNull();
    }
  });

  it("keeps all five workflow stages in order and fully rendered", () => {
    renderPage();

    const workflow = within(document.getElementById("workflow") as HTMLElement);
    const items = workflow.getAllByRole("listitem");
    expect(items).toHaveLength(5);
    const order = items.map((item) => item.textContent ?? "");
    const names = ["Source", "Inspect", "Sanitize", "Govern", "Verify"];
    let cursor = -1;
    for (const name of names) {
      const at = order.findIndex((text, index) => index > cursor && text.includes(name));
      expect(at).toBeGreaterThan(cursor);
      cursor = at;
    }
  });

  it("renders the hero and security content complete without animation", () => {
    renderPage();

    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(
      "Production data, without the exposure.",
    );
    expect(screen.getByText("email → tok_av_7f2c")).toBeTruthy();

    const security = within(document.getElementById("security") as HTMLElement);
    expect(security.getAllByRole("listitem")).toHaveLength(4);
    expect(security.getByText("Owner-scoped resources")).toBeTruthy();
  });
});
