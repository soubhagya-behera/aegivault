import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import WorkflowJourney from "./WorkflowJourney";

// No matchMedia override here: the shared setup reports reduced motion as
// requested, so this file exercises the static fallback. (Motion freezes
// the reduced-motion singleton on first use per test file, so the
// motion-enabled paths live in workflow-journey.test.tsx instead.)
describe("mobile workflow journey under reduced motion", () => {
  it("keeps all essential content accessible with no pinning", () => {
    const { container } = render(<WorkflowJourney />);

    expect(container.querySelector(".mkt-wf-runway")).not.toBeNull();
    expect(container.querySelector(".mkt-wf-pin")).toBeNull();
    for (const name of ["Source", "Inspect", "Sanitize", "Govern", "Verify"]) {
      expect(screen.getAllByText(name).length).toBeGreaterThan(0);
    }
    expect(screen.getByText(/customers\.csv/i)).toBeTruthy();
    expect(screen.getByText(/tok_av_7f2c/i)).toBeTruthy();
    expect(screen.getByText(/schematic — any values shown are fictional/i)).toBeTruthy();
  });
});
