import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { carouselIndexForScroll, indexForProgress, progressForIndex } from "./motion";
import PlatformJourney from "./PlatformJourney";

function mockMatchMedia(matchesQuery: (query: string) => boolean) {
  window.matchMedia = ((query: string): MediaQueryList =>
    ({
      matches: matchesQuery(query),
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList);
}

// Default viewport: desktop motion-safe (mirrors the shared setup, which
// reports reduced motion as requested but no viewport width).
beforeEach(() => {
  mockMatchMedia((query) => query.includes("prefers-reduced-motion"));
});

describe("platform journey helpers (Phase 3F)", () => {
  it("maps scroll progress to equal panel bands, clamped", () => {
    expect(indexForProgress(0, 6)).toBe(0);
    expect(indexForProgress(0.08, 6)).toBe(0);
    expect(indexForProgress(0.2, 6)).toBe(1);
    expect(indexForProgress(0.5, 6)).toBe(3);
    expect(indexForProgress(0.99, 6)).toBe(5);
    expect(indexForProgress(1, 6)).toBe(5);
    expect(indexForProgress(-0.5, 6)).toBe(0);
    expect(indexForProgress(2, 6)).toBe(5);
    expect(indexForProgress(0.5, 0)).toBe(0);
  });

  it("maps a panel index back to a scroll progress at the endpoints", () => {
    expect(progressForIndex(0, 6)).toBe(0);
    expect(progressForIndex(5, 6)).toBe(1);
    expect(progressForIndex(2, 6)).toBeCloseTo(0.4, 5);
    expect(progressForIndex(-3, 6)).toBe(0);
    expect(progressForIndex(99, 6)).toBe(1);
    expect(progressForIndex(0, 1)).toBe(0);
  });
});

describe("manual carousel index (Chapter 02)", () => {
  it("picks the card nearest the track's leading edge, clamped", () => {
    const starts = [0, 576, 1152, 1728, 2304, 2880];
    expect(carouselIndexForScroll(starts, 0)).toBe(0);
    expect(carouselIndexForScroll(starts, 200)).toBe(0);
    expect(carouselIndexForScroll(starts, 400)).toBe(1);
    expect(carouselIndexForScroll(starts, 1500)).toBe(3);
    expect(carouselIndexForScroll(starts, 2880)).toBe(5);
    expect(carouselIndexForScroll(starts, 9999)).toBe(5);
    expect(carouselIndexForScroll(starts, -50)).toBe(0);
    expect(carouselIndexForScroll([], 400)).toBe(0);
  });

  it("selects Audit at the real maximum scroll offset, even when short of its start", () => {
    // Desktop geometry where the Audit start is unreachable: five 560px
    // cards + 16px gaps in a 1200px viewport leave maxScroll below the
    // Audit start, so nearest-start alone would stick on Limits (index 4).
    const starts = [0, 576, 1152, 1728, 2304, 2880];
    const maxScroll = 2880 + 560 - 1200;
    expect(maxScroll).toBeLessThan(starts[5] ?? 0);
    expect(carouselIndexForScroll(starts, maxScroll - 200, maxScroll)).toBe(4);
    expect(carouselIndexForScroll(starts, maxScroll, maxScroll)).toBe(5);
    // Mid-track selection is unaffected by the terminal rule.
    expect(carouselIndexForScroll(starts, 1152, maxScroll)).toBe(2);
    expect(carouselIndexForScroll(starts, 0, maxScroll)).toBe(0);
  });
});

describe("manual carousel content (Chapter 02)", () => {
  it("renders all six capabilities with diagrams", () => {
    render(<PlatformJourney />);

    expect(screen.getAllByRole("article")).toHaveLength(6);
    // Diagrams and key content survive on every card.
    expect(screen.getByText(/not a blockchain/i)).toBeTruthy();
    for (const code of ["PROFILE", "SANITIZE", "POSTGRES", "GATEWAY", "LIMITS", "AUDIT"]) {
      expect(screen.getAllByText(code).length).toBeGreaterThan(0);
    }
    expect(screen.getAllByText(/schematic — any example values are fictional/i)).toHaveLength(6);
    // The audit-chain diagram renders inside the audit card.
    expect(screen.getByText(/verify: replay, report first broken link/i)).toBeTruthy();
  });

  it("shows the compact tagline on mobile without the verbose paragraph", () => {
    render(<PlatformJourney />);

    // The concise mobile tagline is present…
    expect(screen.getByText(/detects sensitive data by column without retaining raw rows/i)).toBeTruthy();
    // …while the long desktop paragraph exists only once per render tree in
    // the hidden desktop copy (sm:), never duplicated as visible mobile text.
    expect(screen.getAllByText(/password-labelled values/i)).toHaveLength(1);
  });

  it("selecting each rail label activates the matching capability in order", () => {
    const trackScrollTo = vi.fn();
    const originalScrollTo = Element.prototype.scrollTo;
    Element.prototype.scrollTo = trackScrollTo as unknown as typeof Element.prototype.scrollTo;
    const originalIntoView = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function () {
      throw new Error("scrollIntoView must not be used for carousel navigation");
    };
    const scrollXBefore = window.scrollX;
    try {
      render(<PlatformJourney />);
      const expected: Array<[RegExp, string]> = [
        [/capability 01/i, "1"],
        [/capability 02/i, "2"],
        [/capability 03/i, "3"],
        [/capability 04/i, "4"],
        [/capability 05/i, "5"],
        [/capability 06/i, "6"],
      ];
      for (const [name, value] of expected) {
        trackScrollTo.mockClear();
        fireEvent.click(screen.getByRole("button", { name }));
        expect(screen.getByRole("button", { name }).getAttribute("aria-current")).toBe("true");
        expect(screen.getByRole("progressbar", { name: "Carousel progress" }).getAttribute("aria-valuenow")).toBe(
          value,
        );
        // Only the carousel container scrolls — never the document.
        expect(trackScrollTo).toHaveBeenCalledOnce();
        expect(window.scrollX).toBe(scrollXBefore);
      }
      // Selecting 03 activates PostgreSQL (not Audit); 06 activates Audit.
      fireEvent.click(screen.getByRole("button", { name: /capability 03/i }));
      expect(screen.getByRole("button", { name: /capability 03/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByRole("button", { name: /capability 06/i }).getAttribute("aria-current")).toBeNull();
      fireEvent.click(screen.getByRole("button", { name: /capability 06/i }));
      expect(screen.getByRole("button", { name: /capability 06/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByText(/verify: replay, report first broken link/i)).toBeTruthy();
    } finally {
      Element.prototype.scrollTo = originalScrollTo;
      Element.prototype.scrollIntoView = originalIntoView;
    }
  });
});

describe("manual carousel arrows (Chapter 02)", () => {
  it("moves one card per press, disables at both ends, and never moves the page", () => {
    const trackScrollTo = vi.fn();
    const originalScrollTo = Element.prototype.scrollTo;
    Element.prototype.scrollTo = trackScrollTo as unknown as typeof Element.prototype.scrollTo;
    const originalIntoView = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function () {
      throw new Error("scrollIntoView must not be used for carousel navigation");
    };
    const scrollXBefore = window.scrollX;
    try {
      render(<PlatformJourney />);

      const prev = screen.getByRole("button", { name: "Previous capability" }) as HTMLButtonElement;
      const next = screen.getByRole("button", { name: "Next capability" }) as HTMLButtonElement;
      expect(prev.disabled).toBe(true);
      expect(next.disabled).toBe(false);

      fireEvent.click(next);
      expect(screen.getByRole("button", { name: /capability 02/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByRole("progressbar", { name: "Carousel progress" }).getAttribute("aria-valuenow")).toBe("2");
      expect(prev.disabled).toBe(false);

      // Horizontal track scroll updates forward through 03 → 06.
      fireEvent.click(next);
      expect(screen.getByRole("button", { name: /capability 03/i }).getAttribute("aria-current")).toBe("true");
      for (let step = 0; step < 3; step++) fireEvent.click(next);
      expect(screen.getByRole("button", { name: /capability 06/i }).getAttribute("aria-current")).toBe("true");
      expect(next.disabled).toBe(true);
      expect(screen.getByRole("progressbar", { name: "Carousel progress" }).getAttribute("aria-valuenow")).toBe("6");

      fireEvent.click(prev);
      expect(screen.getByRole("button", { name: /capability 05/i }).getAttribute("aria-current")).toBe("true");
      expect(next.disabled).toBe(false);

      expect(window.scrollX).toBe(scrollXBefore);
      expect(trackScrollTo).toHaveBeenCalled();
      // A single native track owns the cards — no pinned runway remains.
      expect(document.querySelector(".mkt-journey-outer")).toBeNull();
      expect(document.querySelector(".mkt-carousel-track")).not.toBeNull();
    } finally {
      Element.prototype.scrollTo = originalScrollTo;
      Element.prototype.scrollIntoView = originalIntoView;
    }
  });

  it("keeps the rail on the active capability after backward navigation", () => {
    const originalScrollTo = Element.prototype.scrollTo;
    Element.prototype.scrollTo = () => {};
    const scrollXBefore = window.scrollX;
    try {
      render(<PlatformJourney />);

      // Move through to the end.
      fireEvent.click(screen.getByRole("button", { name: /capability 06/i }));
      expect(screen.getByRole("button", { name: /capability 06/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByRole("button", { name: /capability 05/i }).getAttribute("aria-current")).toBeNull();

      // Then move backward — the rail must reposition its active tab.
      fireEvent.click(screen.getByRole("button", { name: /capability 05/i }));
      expect(screen.getByRole("button", { name: /capability 05/i }).getAttribute("aria-current")).toBe("true");
      fireEvent.click(screen.getByRole("button", { name: /capability 01/i }));
      expect(screen.getByRole("button", { name: /capability 01/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByRole("button", { name: /capability 06/i }).getAttribute("aria-current")).toBeNull();

      // Rail and counter follow the active capability without moving the page.
      expect(window.scrollX).toBe(scrollXBefore);
      expect(screen.getByRole("button", { name: /capability 01/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByRole("button", { name: /capability 06/i }).getAttribute("aria-current")).toBeNull();
      expect(screen.getByRole("progressbar", { name: "Carousel progress" }).getAttribute("aria-valuenow")).toBe("1");
    } finally {
      Element.prototype.scrollTo = originalScrollTo;
    }
  });

  it("keeps the rail on the active capability and follows manual track scroll", async () => {
    const originalScrollTo = Element.prototype.scrollTo;
    Element.prototype.scrollTo = () => {};
    const scrollXBefore = window.scrollX;
    try {
      render(<PlatformJourney />);

      // Rail switches to PostgreSQL before the card's smooth scroll settles.
      fireEvent.click(screen.getByRole("button", { name: /capability 03/i }));
      expect(screen.getByRole("button", { name: /capability 03/i }).getAttribute("aria-current")).toBe("true");
      expect(screen.getByRole("button", { name: /capability 01/i }).getAttribute("aria-current")).toBeNull();

      // Simulate a rightward track scroll so the track's content moves right
      // and Capability 03 becomes the active card (right edge past Profile).
      const track = screen.getByRole("region", { name: "Platform capabilities carousel" }) as HTMLElement;
      const cards = track.querySelectorAll<HTMLElement>(".mkt-carousel-card");
      const firstCard = cards[0];
      const secondCard = cards[1];
      const stride = (secondCard?.offsetLeft ?? 0) - (firstCard?.offsetLeft ?? 0);
      track.scrollLeft = stride;
      fireEvent.scroll(track);

      await vi.waitFor(() => {
        expect(screen.getByRole("button", { name: /capability 03/i }).getAttribute("aria-current")).toBe("true");
      });

      // Rail and counter keep the document's horizontal position unchanged.
      expect(window.scrollX).toBe(scrollXBefore);
      expect(screen.getByRole("progressbar", { name: "Carousel progress" }).getAttribute("aria-valuenow")).toBe("3");
    } finally {
      Element.prototype.scrollTo = originalScrollTo;
    }
  });
});
