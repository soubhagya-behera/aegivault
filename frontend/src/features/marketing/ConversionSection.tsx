import { useRef } from "react";
import { Link } from "react-router-dom";
import { motion, useReducedMotion, useScroll, useTransform } from "motion/react";
import Reveal from "./Reveal";

/**
 * Closing conversion chapter (Phase 3E): a bold cobalt field with giant ice
 * display type, a graphite evidence card for full-contrast body copy, and a
 * graphite CTA with a lime edge. No invented metrics or stories — the real
 * next steps only. An oversized outline wordmark backs the composition
 * (decorative, aria-hidden).
 *
 * Phase 3F close: a lime rule draws across as the chapter enters, and the
 * wordmark drifts horizontally with scroll progress — the scroll story lands
 * instead of stopping. CTAs stay immediately readable and usable throughout.
 */
export default function ConversionSection() {
  const reduce = useReducedMotion();
  const sectionRef = useRef<HTMLElement | null>(null);
  const { scrollYProgress } = useScroll({
    target: sectionRef,
    offset: ["start end", "end start"],
  });
  const wordX = useTransform(scrollYProgress, [0, 1], [48, -48]);

  return (
    <section ref={sectionRef} aria-labelledby="convert-title" data-mkt-chapter="convert" className="relative scroll-mt-24 overflow-hidden">
      <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 -bottom-8 select-none overflow-hidden">
        {reduce ? (
          <p className="mkt-display mkt-outline-word whitespace-nowrap text-center text-[19vw] font-bold leading-none">
            AEGIVAULT
          </p>
        ) : (
          <motion.p
            className="mkt-display mkt-outline-word whitespace-nowrap text-center text-[19vw] font-bold leading-none"
            style={{ x: wordX }}
          >
            AEGIVAULT
          </motion.p>
        )}
      </div>
      <div className="relative mx-auto max-w-app px-6 py-20 md:py-28 lg:py-36">
        <Reveal>
          <p className="flex items-center gap-3 font-mono text-xs uppercase tracking-[0.16em] text-white">
            <span aria-hidden="true" className="h-0.5 w-10 shrink-0 bg-lime" />
            Chapter 05 — Begin
          </p>
          {!reduce && (
            <motion.span
              aria-hidden="true"
              className="mt-6 block h-1 w-24 origin-left bg-lime"
              initial={{ scaleX: 0 }}
              whileInView={{ scaleX: 1 }}
              viewport={{ once: true, margin: "-80px" }}
              transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
            />
          )}
          <h2 id="convert-title" className="mkt-display mt-6 max-w-4xl text-5xl font-semibold text-white sm:text-6xl lg:text-7xl">
            Bring production data under control.
          </h2>
          <div className="mt-10 max-w-xl rounded-xl bg-surface p-6 shadow-[0_32px_90px_rgba(9,12,18,0.45)] md:p-8">
            <p className="text-lg leading-relaxed text-text">
              Profile what you hold, sanitize with an explicit policy, govern
              every AI-bound request, and keep evidence that verifies.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link
                to="/login"
                className="inline-flex min-h-12 items-center justify-center rounded-md bg-lime px-7 text-[15px] font-semibold transition-[transform,filter] duration-200 hover:brightness-110 active:scale-[0.98]"
                style={{ color: "var(--mkt-ink)" }}
              >
                Sign in to Aegivault
              </Link>
              <a
                href="#platform"
                className="inline-flex min-h-12 items-center justify-center rounded-md border border-border-strong px-7 text-[15px] font-medium text-text transition-[transform,border-color] duration-200 hover:border-lime active:scale-[0.98]"
              >
                Revisit the platform
              </a>
            </div>
          </div>
          <p className="mt-8 font-mono text-xs uppercase tracking-[0.14em] text-white/80">
            Fictional illustrations throughout — no production data shown.
          </p>
        </Reveal>
      </div>
    </section>
  );
}
