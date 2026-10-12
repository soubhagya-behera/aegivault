import { useRef } from "react";
import { motion, useReducedMotion, useScroll, useTransform } from "motion/react";
import type { Variants } from "motion/react";
import { Link } from "react-router-dom";
import HeroBackdrop from "./HeroBackdrop";
import SecurityPipelineIllustration from "./SecurityPipelineIllustration";
import { EASE } from "./motion";

/* Cold-load entrance, once: backdrop → eyebrow → headline masks → lead →
   CTAs → pipeline panel → meta strip. Runs on mount only. */
const entrance: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.1, delayChildren: 0.1 } },
};

const rise: Variants = {
  hidden: { opacity: 0, y: 22 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.6, ease: EASE } },
};

const mask: Variants = {
  hidden: { y: "112%" },
  visible: { y: "0%", transition: { duration: 0.75, ease: EASE } },
};

/**
 * Opening chapter (Phase 3E): graphite canvas, oversized display type with
 * masked line reveals, and the live pipeline panel as a first-viewport hook.
 * Content is in the DOM immediately (no-JS / reduced-motion safe); motion
 * only sequences visibility, once, on cold load. CTAs stay usable throughout.
 *
 * Phase 3F exit: as the visitor scrolls into the Platform chapter, the
 * pipeline panel alone drifts down slightly, settles, and softens while the
 * heading stays put — a deliberate handoff, not a jump.
 */
export default function LandingHero() {
  const reduce = useReducedMotion();
  const heroRef = useRef<HTMLElement | null>(null);
  const { scrollYProgress: exitProgress } = useScroll({
    target: heroRef,
    offset: ["start start", "end start"],
  });
  const panelY = useTransform(exitProgress, [0, 1], [0, 72]);
  const panelScale = useTransform(exitProgress, [0, 1], [1, 0.965]);
  const panelOpacity = useTransform(exitProgress, [0, 1], [1, 0.82]);

  return (
    <section
      ref={heroRef}
      aria-labelledby="landing-hero-title"
      data-mkt-chapter="opening"
      className="relative overflow-hidden border-b border-border"
    >
      <HeroBackdrop />
      <div className="relative mx-auto max-w-app px-6 pb-20 pt-14 md:pb-28 md:pt-20">
        <motion.div variants={entrance} initial={reduce ? false : "hidden"} animate="visible">
          <motion.div variants={rise} className="flex flex-wrap items-center justify-between gap-4">
            <p className="flex items-center gap-3 font-mono text-xs uppercase tracking-[0.16em] text-text-muted">
              <span aria-hidden="true" className="h-0.5 w-10 shrink-0 bg-lime" />
              Data privacy / Security / AI governance
            </p>
            <p className="rounded-full border border-border px-3 py-1 font-mono text-[11px] uppercase tracking-[0.14em] text-text-muted">
              Chapter 01 — Opening
            </p>
          </motion.div>

          <div className="mt-10 grid grid-cols-1 gap-14 lg:grid-cols-12 lg:gap-10">
            <div className="lg:col-span-7">
              <h1
                id="landing-hero-title"
                className="mkt-display text-5xl font-semibold text-text sm:text-6xl xl:text-7xl"
              >
                <span className="block overflow-hidden pb-1">
                  <motion.span variants={reduce ? {} : mask} className="block">
                    Production data,{" "}
                  </motion.span>
                </span>
                <span className="block overflow-hidden pb-3">
                  <motion.span variants={reduce ? {} : mask} className="block">
                    without the <span className="text-accent">exposure.</span>
                  </motion.span>
                </span>
              </h1>

              <motion.p
                variants={rise}
                className="mt-6 max-w-prose text-lg leading-relaxed text-text-muted md:text-xl"
              >
                Inspect sensitive data, apply controlled transformations, and
                govern AI-bound requests with an auditable security workflow.
              </motion.p>

              <motion.div variants={rise} className="mt-9 flex flex-wrap gap-3">
                <a
                  href="#platform"
                  className="inline-flex min-h-12 items-center justify-center rounded-md bg-lime px-7 text-[15px] font-semibold transition-[transform,filter] duration-200 hover:brightness-110 active:scale-[0.98]"
                  style={{ color: "var(--mkt-ink)" }}
                >
                  Explore the platform
                </a>
                <Link
                  to="/"
                  className="inline-flex min-h-12 items-center justify-center rounded-md border border-border-strong px-7 text-[15px] font-medium text-text transition-[transform,border-color,background-color] duration-200 hover:border-accent hover:bg-surface-raised active:scale-[0.98]"
                >
                  Open workspace
                </Link>
              </motion.div>

              <motion.dl
                variants={rise}
                className="mt-12 grid max-w-xl grid-cols-3 gap-6 border-t border-border pt-6"
              >
                {[
                  ["Discover", "profile & PII"],
                  ["Govern", "AI requests"],
                  ["Prove", "audit evidence"],
                ].map(([term, def]) => (
                  <div key={term}>
                    <dt className="font-mono text-xs uppercase tracking-[0.14em] text-lime">{term}</dt>
                    <dd className="mt-1.5 text-sm text-text">{def}</dd>
                  </div>
                ))}
              </motion.dl>
            </div>

            <motion.div
              variants={rise}
              className="lg:col-span-5"
              style={reduce ? undefined : { y: panelY, scale: panelScale, opacity: panelOpacity }}
            >
              <SecurityPipelineIllustration />
            </motion.div>
          </div>
        </motion.div>

        <div aria-hidden="true" className="mt-16 flex items-center gap-3">
          <span className="h-px w-16 bg-accent" />
          <span className="font-mono text-xs uppercase tracking-[0.16em] text-text-muted">
            Scroll — five chapters
          </span>
        </div>
      </div>
    </section>
  );
}
