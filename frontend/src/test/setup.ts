import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// jsdom does not implement window.matchMedia, which Motion reads to honor
// prefers-reduced-motion. Minimal stand-in reporting reduced motion as
// requested, so component tests exercise the static fallback path.
if (typeof window.matchMedia !== "function") {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: query.includes("prefers-reduced-motion: reduce"),
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}

// Enable React's act() environment for @testing-library/react.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// Auto-unmount rendered components between tests.
afterEach(() => {
  cleanup();
});
