import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Enable React's act() environment for @testing-library/react.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// Auto-unmount rendered components between tests.
afterEach(() => {
  cleanup();
});
