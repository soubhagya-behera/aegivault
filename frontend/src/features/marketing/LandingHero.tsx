import { motion, useReducedMotion } from "motion/react";
import type { Variants } from "motion/react";
import { Link } from "react-router-dom";
import SecurityPipelineIllustration from "./SecurityPipelineIllustration";

const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1];

/* One coordinated reveal: staggered groups, not per-element animation. */
const revealGroup: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.07, delayChildren: 0.05 } },
};

const revealItem: Variants = {
  hidden: { opacity: 0, y: 12 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.42, ease: EASE } },
};

/* The headline staggers its two lines inside the group above. */
const headlineGroup: Variants = {
  hidden: { transition: { staggerChildren: 0.09, delayChildren: 0.1 } },
  visible: { transition: { staggerChildren: 0.09, delayChildren: 0.1 } },
};

const headlineLine: Variants = {
  hidden: { opacity: 0, y: 14 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.5, ease: EASE } },
};

/**
 * Landing hero (Phase 3A): editorial, deliberately asymmetric composition —
 * text in columns 1–6, a gutter, the pipeline illustration in columns 8–12.
 *
 * Motion is a single short reveal sequence (ANIMATION_SPEC §3.1); with
 * `prefers-reduced-motion` the content renders immediately in its final state.
 * CTAs are real destinations: the primary is the `#platform` section anchor
 * implemented in a later phase; the secondary is the protected workspace route,
 * whose guard decides access.
 */
export default function LandingHero() {
  const reduce = useReducedMotion();

  return (
    <section aria-labelledby="landing-hero-title" className="border-b border-border">
      <div className="mx-auto max-w-app px-6 pb-16 pt-12 md:pb-20 md:pt-16 lg:pb-24 lg:pt-20">
        <div className="grid grid-cols-1 gap-12 lg:grid-cols-12 lg:gap-8">
          <motion.div
            className="lg:col-span-6"
            variants={revealGroup}
            initial={reduce ? false : "hidden"}
            animate="visible"
          >
            <p className="flex items-center gap-3 font-mono text-xs uppercase tracking-wide text-text-muted">
              <span aria-hidden="true" className="h-px w-8 shrink-0 bg-accent" />
              Data privacy / Security / AI governance
            </p>

            <motion.h1
              id="landing-hero-title"
              variants={headlineGroup}
              className="mt-6 font-display text-2xl font-semibold leading-tight tracking-tight text-text sm:text-3xl lg:text-4xl"
            >
              <motion.span variants={headlineLine} className="block">
                Production data,{" "}
              </motion.span>
              <motion.span variants={headlineLine} className="block">
                without the exposure.
              </motion.span>
            </motion.h1>

            <motion.p
              variants={revealItem}
              className="mt-6 max-w-prose text-lg leading-relaxed text-text-muted"
            >
              Inspect sensitive data, apply controlled transformations, and govern
              AI-bound requests with an auditable security workflow.
            </motion.p>

            <motion.div variants={revealItem} className="mt-9 flex flex-wrap gap-3">
              <a
                href="#platform"
                className="inline-flex min-h-11 items-center justify-center rounded-md bg-accent px-5 text-sm font-medium text-accent-contrast transition-colors hover:bg-accent-hover"
              >
                Explore the platform
              </a>
              <Link
                to="/"
                className="inline-flex min-h-11 items-center justify-center rounded-md border border-border px-5 text-sm font-medium text-text transition-colors hover:border-border-strong hover:bg-surface-raised"
              >
                Open workspace
              </Link>
            </motion.div>
          </motion.div>

          <div className="lg:col-span-5 lg:col-start-8 lg:pt-2">
            <SecurityPipelineIllustration />
          </div>
        </div>
      </div>
    </section>
  );
}
