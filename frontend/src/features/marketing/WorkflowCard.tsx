import { useEffect, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import { AnimatePresence, motion, motionValue, useReducedMotion, type MotionValue } from "motion/react";
import WorkflowPipeline from "./WorkflowPipeline";
import { WORKFLOW_STEPS } from "./workflowData";
import { EASE } from "./motion";

interface StageRailProps {
  active: number;
  onSelect: (index: number) => void;
}

/** Compact five-stage rail: optional navigation, never required to advance. */
export function StageRail({ active, onSelect }: StageRailProps) {
  const reduce = useReducedMotion();
  const stageRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const mounted = useRef(false);

  // Keep the active tab visible inside the rail container as the
  // authoritative active stage changes (scroll forward/back or tap).
  // Scrolls the rail only (`block: "nearest"` never moves the page
  // vertically); fires per stage change, so no jitter while reading.
  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true;
      return;
    }
    const el = stageRefs.current[active];
    if (!el || typeof el.scrollIntoView !== "function") return;
    el.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "nearest", inline: "center" });
  }, [active, reduce]);

  function focusStage(index: number) {
    const clamped = Math.min(WORKFLOW_STEPS.length - 1, Math.max(0, index));
    onSelect(clamped);
    stageRefs.current[clamped]?.focus();
  }

  function handleRailKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    switch (event.key) {
      case "ArrowRight":
      case "ArrowDown":
        event.preventDefault();
        focusStage(active + 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        event.preventDefault();
        focusStage(active - 1);
        break;
      case "Home":
        event.preventDefault();
        focusStage(0);
        break;
      case "End":
        event.preventDefault();
        focusStage(WORKFLOW_STEPS.length - 1);
        break;
      default:
        return;
    }
  }

  return (
    <div
      role="group"
      aria-label="Workflow stages"
      onKeyDown={handleRailKeyDown}
      // flex-none: the rail is a horizontal scroll container, so its
      // automatic flex minimum is zero — without this it collapses inside
      // fixed-height flex parents and the following rows paint over it.
      className="mkt-snap-rail flex flex-none gap-2 overflow-x-auto pb-2"
    >
      {WORKFLOW_STEPS.map((candidate, index) => {
        const isActive = index === active;
        return (
          <button
            key={candidate.index}
            ref={(element) => {
              stageRefs.current[index] = element;
            }}
            type="button"
            onClick={() => onSelect(index)}
            aria-current={isActive ? "step" : undefined}
            aria-label={`Stage ${candidate.index}: ${candidate.name}`}
            className={`flex min-h-11 shrink-0 snap-start items-center gap-2 rounded-lg border px-3.5 font-mono text-xs font-bold uppercase tracking-[0.1em] transition-[background-color,border-color,color,transform] duration-200 active:scale-[0.97] ${
              isActive
                ? "border-lime bg-surface text-text shadow-[0_10px_28px_rgba(65,105,255,0.28)]"
                : "border-border bg-transparent text-text-muted"
            }`}
          >
            <span aria-hidden="true" className={isActive ? "text-lime" : ""}>
              {candidate.index}
            </span>
            {candidate.name}
          </button>
        );
      })}
    </div>
  );
}

function StageExplanation({ index, animated }: { index: number; animated: boolean }) {
  const step = WORKFLOW_STEPS[index] ?? WORKFLOW_STEPS[0];
  if (step === undefined) return null;
  if (!animated) {
    return (
      <div key={step.index} className="mt-3 border-t border-border pt-3">
        <h3 className="font-display text-xl font-semibold tracking-tight text-text">{step.name}</h3>
        <p className="mt-1.5 text-[15px] leading-relaxed text-text">{step.line}</p>
        <p className="mt-1 text-sm leading-relaxed text-text-muted">{step.detail}</p>
      </div>
    );
  }
  return (
    <div className="mt-3 border-t border-border pt-3">
      {/* popLayout mounts the incoming explanation synchronously so rapid
          taps resolve to the latest stage with no blank gap; the short
          fade/slide never clips content into an empty outline. */}
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.div
          key={step.index}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -6 }}
          transition={{ duration: 0.2, ease: EASE }}
        >
          <h3 className="font-display text-xl font-semibold tracking-tight text-text">{step.name}</h3>
          <p className="mt-1.5 text-[15px] leading-relaxed text-text">{step.line}</p>
          <p className="mt-1 text-sm leading-relaxed text-text-muted">{step.detail}</p>
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

interface WorkflowCardProps {
  /** Controlled active stage (from the section scroll driver). Uncontrolled when omitted. */
  active?: number;
  /** Section scroll progress for the spine fill + packet. Local fallback when omitted. */
  progress?: MotionValue<number>;
  /** Controlled selection (taps). Defaults to local state. Never moves the page. */
  onSelect?: (index: number) => void;
}

/**
 * Mobile workflow card (Chapter 03 recovery, below the desktop `lg`
 * breakpoint). A compact in-flow composition — stage rail directly above
 * the flat schematic with the explanation connected below. No pinning, no
 * measured outer height, no fixed viewport: the card stays close to the
 * chapter intro and releases naturally into the next chapter.
 *
 * When embedded in `WorkflowSection`, `active`/`progress` arrive from the
 * single section scroll driver so rail, schematic, and explanation stay in
 * sync during ordinary vertical scrolling (forward and back). Standalone
 * (tests, reduced motion), taps drive the same state locally. Tapping
 * selects without moving the page. Reduced motion renders instant swaps.
 */
export default function WorkflowCard({ active: controlled, progress: controlledProgress, onSelect }: WorkflowCardProps) {
  const reduce = useReducedMotion();
  const [internal, setInternal] = useState(0);
  const [fallbackProgress] = useState<MotionValue<number>>(() => motionValue(0));
  const rawActive = controlled ?? internal;
  const active = Math.min(WORKFLOW_STEPS.length - 1, Math.max(0, rawActive));
  const progress = controlledProgress ?? fallbackProgress;

  function select(index: number) {
    const clamped = Math.min(WORKFLOW_STEPS.length - 1, Math.max(0, index));
    if (onSelect) {
      onSelect(clamped);
      return;
    }
    setInternal(clamped);
  }

  return (
    <div>
      <StageRail active={active} onSelect={select} />
      <div className="mt-3">
        <WorkflowPipeline active={reduce ? -1 : active} progress={progress} compact />
      </div>
      <StageExplanation index={active} animated={!reduce} />
    </div>
  );
}
