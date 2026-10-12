import { useEffect, useRef, useState } from "react";
import { motion, useReducedMotion, useScroll, useSpring } from "motion/react";
import SectionIntro from "./SectionIntro";
import Reveal from "./Reveal";
import WorkflowPipeline from "./WorkflowPipeline";
import WorkflowJourney from "./WorkflowJourney";
import { WORKFLOW_STEPS } from "./workflowData";
import { EASE, VIEWPORT_ONCE, nearestCenterIndex, staggerChild, staggerContainer } from "./motion";

/**
 * #workflow: midnight storytelling with one authoritative stage source per
 * viewport. Desktop derives the discrete active stage from the real panel
 * positions — on every scroll/resize, the visible desktop row nearest the
 * viewport center wins, via the pure `nearestCenterIndex` helper — so the
 * sticky schematic, counter, and explanation always name the same stage,
 * forward and back, with Verify reachable at the bottom. Section-level
 * `useScroll` drives only decorative motion (progress bar, spine fill,
 * packet) and never the selection. Below `lg`, the dedicated pinned
 * `WorkflowJourney` owns its own runway-scoped progress driver. The flat
 * schematic keeps every stage's technical content visible — no 3D layers.
 */
export default function WorkflowSection() {
  const reduce = useReducedMotion();
  const [active, setActive] = useState(0);
  // Desktop stage rows (hidden below lg; mobile journey tracks itself).
  const panelRefs = useRef<(HTMLElement | null)[]>([]);
  const sectionRef = useRef<HTMLElement | null>(null);

  // Decorative only: progress bar, spine fill, and packet position. Page
  // position never selects the stage — see the panel-position effect below.
  const { scrollYProgress } = useScroll({
    target: sectionRef,
    offset: ["start end", "end start"],
  });
  const progress = useSpring(scrollYProgress, { stiffness: 120, damping: 26, mass: 0.4 });

  // Single selection mechanism: nearest visible panel center wins.
  useEffect(() => {
    function update() {
      if (typeof window === "undefined") return;
      const center = window.innerHeight / 2;
      const centers: number[] = [];
      const stages: number[] = [];
      for (let slot = 0; slot < panelRefs.current.length; slot++) {
        const el = panelRefs.current[slot];
        if (!el) continue;
        const rect = el.getBoundingClientRect();
        if (rect.height <= 0) continue;
        centers.push(rect.top + rect.height / 2);
        stages.push(slot % WORKFLOW_STEPS.length);
      }
      if (centers.length === 0) return;
      const next = stages[nearestCenterIndex(centers, center)] ?? 0;
      setActive((previous) => (previous === next ? previous : next));
    }
    update();
    window.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    return () => {
      window.removeEventListener("scroll", update);
      window.removeEventListener("resize", update);
    };
  }, []);

  return (
    <section
      id="workflow"
      ref={sectionRef}
      aria-labelledby="workflow-title"
      data-mkt-chapter="workflow"
      className="scroll-mt-24 border-b border-border"
    >
      <div className="mx-auto max-w-app px-6 py-14 md:py-20 lg:py-28">
        <Reveal>
          <SectionIntro
            eyebrow="Chapter 03 — Workflow"
            title="Source to evidence, one controlled sequence."
            headingId="workflow-title"
          >
            The same five-stage sequence runs behind every dataset and every AI
            request. Follow the schematic as each stage takes over.
          </SectionIntro>
        </Reveal>

        <div aria-hidden="true" className="mt-8 h-1 overflow-hidden rounded-full bg-surface lg:mt-14">
          {reduce ? (
            <div className="h-full w-full bg-accent" />
          ) : (
            <motion.div className="h-full origin-left bg-accent" style={{ scaleX: progress }} />
          )}
        </div>

        <div className="mt-6 lg:hidden">
          <WorkflowJourney />
        </div>

        <div className="mt-8 hidden grid-cols-1 gap-10 lg:mt-12 lg:grid lg:grid-cols-12 lg:gap-12">
          <div className="mkt-sticky-visual lg:col-span-5">
            <WorkflowPipeline active={active} progress={scrollYProgress} />
          </div>

          <motion.ol
            className="lg:col-span-7"
            variants={staggerContainer(0.06, 0.05)}
            initial={reduce ? false : "hidden"}
            whileInView="visible"
            viewport={VIEWPORT_ONCE}
          >
            {WORKFLOW_STEPS.map((step, i) => {
              const isActive = i === active;
              return (
                <motion.li
                  key={step.index}
                  ref={(el) => {
                    panelRefs.current[i] = el;
                  }}
                  variants={staggerChild}
                  aria-current={isActive ? "step" : undefined}
                  className={`border-t py-8 transition-colors duration-300 lg:flex lg:min-h-[52vh] lg:flex-col lg:justify-center lg:py-10 ${
                    isActive ? "border-lime" : "border-border-strong"
                  } ${i === WORKFLOW_STEPS.length - 1 ? "border-b" : ""}`}
                >
                  <span className={`font-mono text-sm ${isActive ? "text-lime" : "text-text-muted"}`}>
                    {step.index}
                  </span>
                  <h3 className="mt-2 font-display text-3xl font-semibold tracking-tight text-text sm:text-4xl">
                    {step.name}
                  </h3>
                  <p className="mt-3 max-w-prose text-lg leading-relaxed text-text">{step.line}</p>
                  <p className="mt-2 max-w-prose text-[15px] leading-relaxed text-text-muted">{step.detail}</p>
                  {isActive && !reduce ? (
                    <motion.span
                      layoutId="workflow-active-glow"
                      aria-hidden="true"
                      className="mt-4 block h-1 w-16 bg-lime"
                      transition={{ duration: 0.3, ease: EASE }}
                    />
                  ) : null}
                </motion.li>
              );
            })}
          </motion.ol>
        </div>
      </div>
    </section>
  );
}
