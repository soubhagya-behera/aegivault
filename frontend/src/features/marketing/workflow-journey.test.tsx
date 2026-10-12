import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import WorkflowJourney from "./WorkflowJourney";
import { indexForProgress, progressForIndex } from "./motion";

function mockMatchMedia(matches: boolean) {
  window.matchMedia = ((query: string): MediaQueryList =>
    ({
      matches,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList);
}

// Motion-enabled viewport: exercise the pinned journey (the reduced-motion
// fallback is covered separately below).
beforeEach(() => {
  mockMatchMedia(false);
});

describe("mobile journey stage mapping (five deliberate intervals)", () => {
  it("starts at Source and advances one stage per scroll band", () => {
    expect(indexForProgress(0, 5)).toBe(0);
    expect(indexForProgress(0.19, 5)).toBe(0);
    expect(indexForProgress(0.2, 5)).toBe(1);
    expect(indexForProgress(0.4, 5)).toBe(2);
    expect(indexForProgress(0.6, 5)).toBe(3);
    expect(indexForProgress(0.79, 5)).toBe(3);
  });

  it("keeps Verify reachable through the final interval up to release", () => {
    expect(indexForProgress(0.8, 5)).toBe(4);
    expect(indexForProgress(0.99, 5)).toBe(4);
    expect(indexForProgress(1, 5)).toBe(4);
  });

  it("maps rail taps back into runway positions at the band starts", () => {
    expect(progressForIndex(0, 5)).toBe(0);
    expect(progressForIndex(4, 5)).toBe(1);
    expect(progressForIndex(2, 5)).toBeCloseTo(0.5, 5);
  });
});

describe("mobile workflow journey (pinned, below lg)", () => {
  it("pins a vertical five-card stack with rail, counter, spine, and explanation", () => {
    const { container } = render(<WorkflowJourney />);

    // Deliberate runway + sticky pin (release comes from normal scrolling
    // past the runway end — no snapping, no traps).
    expect(container.querySelector(".mkt-wf-runway")).not.toBeNull();
    expect(container.querySelector(".mkt-wf-pin")).not.toBeNull();

    // Source begins active; all five cards stay visible together.
    const rail = screen.getByRole("group", { name: "Workflow stages" });
    expect(within(rail).getAllByRole("button")).toHaveLength(5);
    expect(screen.getByRole("button", { name: /stage 01/i }).getAttribute("aria-current")).toBe("step");
    expect(screen.getByRole("heading", { level: 3, name: "01 Source" })).toBeTruthy();
    expect(screen.getByText(/stage 1 \/ 5/i)).toBeTruthy();
    expect(container.querySelectorAll(".mkt-wf-stack .mkt-module")).toHaveLength(5);

    // Vertical spine only: the packet travels downward, never sideways.
    expect(container.querySelector(".mkt-pipe-route-vertical")).not.toBeNull();
    expect(container.querySelector(".mkt-pipe-packet-vertical")).not.toBeNull();
    expect(container.querySelector(".mkt-pipe-route")).toBeNull();
    expect(container.querySelector(".mkt-pipe-packet")).toBeNull();

    // Rail, counter, spine, cards, and explanation agree on Source, with
    // every card's fictional content present in the stack.
    expect(screen.getByText(/customers\.csv/i)).toBeTruthy();
    expect(screen.getByText(/tok_av_7f2c/i)).toBeTruthy();
    expect(screen.getByText(/a csv upload or a bound postgresql table/i)).toBeTruthy();
    expect(screen.getByText(/schematic — any values shown are fictional/i)).toBeTruthy();
  });

  it("rail taps jump to the stage scroll position without freezing scroll", () => {
    const scrollTo = vi.fn();
    Object.defineProperty(window, "scrollTo", { value: scrollTo, writable: true });

    render(<WorkflowJourney />);
    fireEvent.click(screen.getByRole("button", { name: /stage 05/i }));

    expect(scrollTo).toHaveBeenCalledOnce();
    const [target] = scrollTo.mock.calls[0] as [{ top: number; behavior: string }];
    expect(target.top).toBeGreaterThan(0);
    expect(target.behavior).toBe("smooth");
    expect(screen.getByRole("button", { name: /stage 05/i }).getAttribute("aria-current")).toBe("step");
  });

  it("brings the active tab into view inside the rail without moving the page", () => {
    const scrollSpy = vi.fn();
    const proto = window.HTMLElement.prototype as unknown as Record<string, unknown>;
    const original = proto["scrollIntoView"];
    proto["scrollIntoView"] = scrollSpy;
    try {
      render(<WorkflowJourney />);
      expect(scrollSpy).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole("button", { name: /stage 04/i }));
      expect(scrollSpy).toHaveBeenCalled();
      // Container-only reveal: horizontal centering with no vertical page jump.
      const last = scrollSpy.mock.calls[scrollSpy.mock.calls.length - 1]?.[0] as
        | { behavior: string; block: string; inline: string }
        | undefined;
      expect(last).toMatchObject({ behavior: "smooth", block: "nearest", inline: "center" });
      expect(screen.getByRole("button", { name: /stage 04/i }).getAttribute("aria-current")).toBe("step");

      scrollSpy.mockClear();
      fireEvent.click(screen.getByRole("button", { name: /stage 05/i }));
      expect(scrollSpy).toHaveBeenCalled();
      expect(screen.getByRole("button", { name: /stage 05/i }).getAttribute("aria-current")).toBe("step");

      // Backward travel recenters the earlier tab the same way.
      scrollSpy.mockClear();
      fireEvent.click(screen.getByRole("button", { name: /stage 04/i }));
      expect(scrollSpy).toHaveBeenCalled();
    } finally {
      if (original === undefined) delete proto["scrollIntoView"];
      else proto["scrollIntoView"] = original;
    }
  });

  it("keeps the explanation after the card stack in document flow", () => {
    const { container } = render(<WorkflowJourney />);

    const stack = container.querySelector(".mkt-wf-stack") as Element;
    const disclosure = screen.getByText(/schematic — any values shown are fictional/i);
    const heading = screen.getByRole("heading", { level: 3, name: "01 Source" });
    // Required vertical order: header/counter → spine + cards →
    // disclosure → explanation → Chapter 04.
    expect(stack.compareDocumentPosition(disclosure) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(stack.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(disclosure.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
