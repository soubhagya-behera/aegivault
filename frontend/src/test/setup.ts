import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// jsdom does not implement window.matchMedia, which Motion reads to honor
// prefers-reduced-motion. Minimal stand-in reporting reduced motion as
// requested, so component tests exercise the static fallback path.
// NOTE: Motion v14 queries "(prefers-reduced-motion)" (without ": reduce"),
// so the match must cover that exact string.
if (typeof window.matchMedia !== "function") {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: query.includes("prefers-reduced-motion"),
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}

// jsdom does not implement IntersectionObserver, which Motion's whileInView
// reads for scroll-triggered reveals. Minimal stand-in reporting every
// observed element as intersecting, so tests exercise the revealed (final)
// state rather than crashing on the missing API.
if (typeof window.IntersectionObserver !== "function") {
  window.IntersectionObserver = class {
    private callback: IntersectionObserverCallback;

    constructor(callback: IntersectionObserverCallback) {
      this.callback = callback;
    }

    observe(target: Element) {
      this.callback(
        [{ isIntersecting: true, target } as IntersectionObserverEntry],
        this as unknown as IntersectionObserver,
      );
    }

    unobserve() {}

    disconnect() {}
  } as unknown as typeof IntersectionObserver;
}

// Enable React's act() environment for @testing-library/react.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// Auto-unmount rendered components between tests.
afterEach(() => {
  cleanup();
});
