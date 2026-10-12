import { beforeEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { motionValue } from "motion/react";
import WorkflowPipeline from "./WorkflowPipeline";
import { stageForProgress } from "./motion";

/**
 * Phase 3I pipeline tests. The shared setup forces reduced motion, so opt
 * back into motion here to exercise the 3D scene (the static fallback is
 * covered by the reduced-motion paths of the chapter suite). Poses are
 * MotionValue-driven — structure is asserted here; real 3D motion needs
 * browser review.
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
describe("workflow pipeline (Phase 3I)", () => {
  it("renders five dimensional modules with the route and packet", () => {
    const { container } = render(<WorkflowPipeline active={2} progress={motionValue(0.5)} />);

    expect(screen.getByText(/workflow schematic/i)).toBeTruthy();
    expect(screen.getByText(/stage 3 \/ 5/i)).toBeTruthy();
    expect(container.querySelectorAll(".mkt-module")).toHaveLength(5);
    expect(container.querySelector(".mkt-pipe-packet")).not.toBeNull();

    // Every stage keeps its meaningful technical readout in the scene.
    expect(screen.getByText(/customers\.csv/i)).toBeTruthy();
    expect(screen.getByText(/tok_av_7f2c/i)).toBeTruthy();
    expect(screen.getByText(/pii_direct/i)).toBeTruthy();
    expect(screen.getByText(/link 3\/3 verified/i)).toBeTruthy();
    expect(screen.getByText(/schematic — any values shown are fictional/i)).toBeTruthy();
  });

  it("marks exactly the auto-active module current", () => {
    const { container } = render(<WorkflowPipeline active={4} progress={motionValue(1)} />);

    const current = container.querySelectorAll('.mkt-module[data-active="true"]');
    expect(current).toHaveLength(1);
    expect(screen.getByText(/stage 5 \/ 5/i)).toBeTruthy();
  });
});

describe("stage sync mapping (Phase 3I.1)", () => {
  it("rounds to the module center so labels agree with the focused stage", () => {
    expect(stageForProgress(0, 5)).toBe(0);
    expect(stageForProgress(0.1, 5)).toBe(0);
    // Floor bands would still report 0 here; the focused module is stage 02.
    expect(stageForProgress(0.13, 5)).toBe(1);
    expect(stageForProgress(0.5, 5)).toBe(2);
    expect(stageForProgress(0.9, 5)).toBe(4);
    expect(stageForProgress(1, 5)).toBe(4);
    expect(stageForProgress(-0.4, 5)).toBe(0);
    expect(stageForProgress(1.6, 5)).toBe(4);
    expect(stageForProgress(0.4, 0)).toBe(0);
  });
});
