import { useRef, useState } from "react";
import { AnimatePresence, motion, useMotionValueEvent, useReducedMotion, useScroll } from "motion/react";
import WorkflowCard, { StageRail } from "./WorkflowCard";
import { WorkflowSpine } from "./WorkflowPipeline";
import { ModuleFront } from "./PipelineModule";
import { WORKFLOW_STEPS } from "./workflowData";
import { EASE, indexForProgress, progressForIndex } from "./motion";

function clampStage(index: number): number {
  return Math.min(WORKFLOW_STEPS.length - 1, Math.max(0, index));
}

function StageExplanation({ index, animated }: { index: number; animated: boolean }) {
  const step = WORKFLOW_STEPS[index] ?? WORKFLOW_STEPS[0];
  if (step === undefined) return null;
  const body = (
    <>
      <h3 className="font-display text-lg font-semibold tracking-tight text-text">
        <span className="mr-2 font-mono text-xs font-normal text-lime">{step.index}</span> {step.name}
      </h3>
      <p className="mt-1 text-sm leading-relaxed text-text">{step.line}</p>
      <p className="mt-0.5 text-[13px] leading-relaxed text-text-muted">{step.detail}</p>
    </>
  );
  if (!animated) return <div key={step.index}>{body}</div>;
  // Vertical-only transition echoing the downward journey: restrained fade
  // with a short rise. Never any horizontal card movement.
  return (
    <AnimatePresence mode="popLayout" initial={false}>
      <motion.div
        key={step.index}
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -10 }}
        transition={{ duration: 0.22, ease: EASE }}
      >
        {body}
      </motion.div>
    </AnimatePresence>
  );
}

/**
 * Mobile-only scroll-driven workflow journey (below the `lg` breakpoint).
 *
 * A deliberate scroll runway (five sticky-heights) pins a vertical stack of
 * all five stage cards while ordinary vertical scrolling advances Source →
 * Verify: the runway's own `useScroll` progress maps to five equal floor
 * bands, so the lime outline, vertical packet, rail, counter, and
 * explanation progress downward together and Verify owns the final interval
 * before the pin releases into Chapter 04. Cards never move — only the
 * active emphasis travels. Backward scrolling reverses symmetrically. Rail
 * taps scroll to the stage's runway position — scrolling stays
 * authoritative, taps can never freeze it. No wheel/touch interception, no
 * scroll snapping, no horizontal motion, no 3D. Reduced motion renders the
 * static tap-driven card with full content.
 */
export default function WorkflowJourney() {
  const reduce = useReducedMotion();
  const [active, setActive] = useState(0);
  const activeRef = useRef(0);
  const runwayRef = useRef<HTMLDivElement | null>(null);

  const { scrollYProgress } = useScroll({
    target: runwayRef,
    offset: ["start start", "end end"],
  });

  // Discrete selection only — fires when the stage band changes, never per frame.
  useMotionValueEvent(scrollYProgress, "change", (value) => {
    if (reduce) return;
    const next = indexForProgress(value, WORKFLOW_STEPS.length);
    if (next === activeRef.current) return;
    activeRef.current = next;
    setActive(next);
  });

  function goTo(index: number) {
    const clamped = clampStage(index);
    if (clamped !== activeRef.current) {
      activeRef.current = clamped;
      setActive(clamped);
    }
    const runway = runwayRef.current;
    if (!runway || typeof window === "undefined") return;
    const top = runway.getBoundingClientRect().top + window.scrollY;
    const distance = Math.max(1, runway.offsetHeight - window.innerHeight);
    const target = top + progressForIndex(clamped, WORKFLOW_STEPS.length) * distance;
    window.scrollTo({ top: target, behavior: reduce ? "auto" : "smooth" });
  }

  if (reduce) {
    // Static readable fallback: the full tap-driven card, no pinning, no
    // runway. The CSS guard below collapses the runway height as well.
    return (
      <div ref={runwayRef} className="mkt-wf-runway lg:hidden">
        <WorkflowCard />
      </div>
    );
  }

  return (
    <div ref={runwayRef} className="mkt-wf-runway lg:hidden">
      <div className="mkt-wf-pin">
        <div className="mx-auto flex h-full w-full flex-col justify-start py-2">
          <StageRail active={active} onSelect={goTo} />
          <div className="mt-2 flex items-center justify-between gap-3">
            <p className="whitespace-nowrap font-mono text-xs uppercase tracking-[0.14em] text-text-muted">Workflow schematic</p>
            <span className="rounded-sm bg-accent px-2 py-0.5 font-mono text-xs font-bold uppercase tracking-wide text-accent-contrast">
              {`Stage ${active + 1} / 5`}
            </span>
          </div>
          {/* Natural heights throughout: the stack keeps its full size in
              the document flow, so the explanation below can never be
              squeezed up over the cards. If the viewport is very short the
              pin spills visibly downward (never clipped) and still releases
              normally at the runway end. */}
          <div className="mt-2 flex gap-2.5">
            <WorkflowSpine vertical active={active} progress={scrollYProgress} />
            <div className="mkt-wf-stack flex flex-1 flex-col gap-1">
              {WORKFLOW_STEPS.map((step, i) => (
                <div key={step.index} data-active={i === active} className="mkt-module">
                  <ModuleFront index={i} lit={i <= active} />
                </div>
              ))}
            </div>
          </div>
          <p className="mt-1 font-mono text-[10px] leading-relaxed text-text-muted">
            Schematic — any values shown are fictional.
          </p>
          <div className="mt-2">
            <StageExplanation index={active} animated />
          </div>
        </div>
      </div>
    </div>
  );
}
