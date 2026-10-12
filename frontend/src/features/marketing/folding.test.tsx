import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import WorkflowCard from "./WorkflowCard";

/**
 * Phase 3G folding-card tests. The shared setup forces reduced motion, so
 * opt back into motion here to exercise the folding branches (the reduced
 * path keeps the tested legacy contract in showcase.test.tsx).
 */
beforeEach(() => {
  window.matchMedia = ((query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList);
});

describe("workflow folding card (Phase 3G)", () => {
  it("starts on Source and updates schematic plus explanation in place", () => {
    const scrollTo = vi.fn();
    Object.defineProperty(window, "scrollTo", { value: scrollTo, writable: true });
    render(<WorkflowCard />);

    const group = screen.getByRole("group", { name: "Workflow stages" });
    expect(within(group).getAllByRole("button")).toHaveLength(5);
    expect(screen.getByRole("heading", { level: 3, name: "Source" })).toBeTruthy();

    // Tapping a stage must not move the page.
    fireEvent.click(screen.getByRole("button", { name: /stage 05/i }));
    expect(scrollTo).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { level: 3, name: "Verify" })).toBeTruthy();
    expect(screen.getByText(/first broken link is reported/i)).toBeTruthy();
    expect(screen.getByText(/link 3\/3 verified/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /stage 03/i }));
    expect(screen.getByRole("heading", { level: 3, name: "Sanitize" })).toBeTruthy();
    expect(screen.getByText(/tok_av_7f2c/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /stage 04/i }));
    expect(screen.getByRole("heading", { level: 3, name: "Govern" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /stage 02/i }));
    expect(screen.getByRole("heading", { level: 3, name: "Inspect" })).toBeTruthy();
  });

  it("marks exactly one stage current", () => {
    render(<WorkflowCard />);

    const buttons = within(screen.getByRole("group", { name: "Workflow stages" })).getAllByRole("button");
    expect(buttons.filter((button) => button.getAttribute("aria-current") === "step")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: /stage 04/i }));
    expect(screen.getByRole("button", { name: /stage 04/i }).getAttribute("aria-current")).toBe("step");
    expect(screen.getByRole("button", { name: /stage 01/i }).getAttribute("aria-current")).toBeNull();
  });
});
