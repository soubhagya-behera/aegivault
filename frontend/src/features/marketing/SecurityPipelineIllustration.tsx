import { motion, useReducedMotion } from "motion/react";
import type { Variants } from "motion/react";
import { EASE } from "./motion";

/**
 * Entrance: lanes boot in sequence after the headline (~0.55s), each lane's
 * one-shot accents (badges, morph, verdict, chain) resolve behind it. Loops
 * (chip drift, scan sweep) are quiet CSS inside the panel only.
 */
const lanes: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.14, delayChildren: 0.55 } },
};

const lane: Variants = {
  hidden: { opacity: 0, x: 22 },
  visible: { opacity: 1, x: 0, transition: { duration: 0.5, ease: EASE } },
};

function oneShot(delay: number, reduce: boolean | null) {
  if (reduce) return {};
  return {
    initial: { opacity: 0, scale: 0.92 },
    animate: { opacity: 1, scale: 1 },
    transition: { duration: 0.35, delay, ease: EASE },
  };
}

const CHIP =
  "shrink-0 rounded border border-border bg-surface-raised px-2 py-1 font-mono text-[11px] text-text";

/**
 * Live security pipeline: SOURCE records drift in, INSPECT scans and badges
 * findings, SANITIZE morphs raw values into tokens, GOVERN resolves
 * ALLOW/BLOCK verdicts, VERIFY links the audit chain. The lane list carries
 * the meaning; every value is fictional (see caption). Fully static and
 * legible under reduced motion.
 */
export default function SecurityPipelineIllustration() {
  const reduce = useReducedMotion();

  return (
    <figure className="overflow-hidden rounded-xl border border-border bg-surface shadow-[0_32px_100px_rgba(65,105,255,0.22)]">
      <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-3">
        <span className="flex items-center gap-2 font-mono text-xs uppercase tracking-wide text-text">
          {!reduce && <span aria-hidden="true" className="mkt-caret inline-block size-2 bg-lime" />}
          Live pipeline
        </span>
        <span className="rounded-sm border border-border px-2 py-0.5 font-mono text-xs uppercase tracking-wide text-text-muted">
          Fictional feed
        </span>
      </div>

      <motion.ol
        className="divide-y divide-border"
        variants={lanes}
        initial={reduce ? false : "hidden"}
        animate="visible"
      >
        {/* 01 SOURCE — records arrive */}
        <motion.li variants={lane} className="px-5 py-3">
          <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-accent">01 · Source</p>
          <div aria-hidden="true" className="mt-2.5 flex gap-2 overflow-hidden">
            {["customers.csv", "row_0142 · 3 cols", "orders_q3"].map((chip, i) => (
              <span key={chip} className={`${CHIP} ${reduce ? "" : "mkt-chips"}`} style={reduce ? undefined : { animationDelay: `${i * 0.9}s` }}>
                {chip}
              </span>
            ))}
          </div>
          <p className="mt-2 text-[13px] text-text-muted">Rows arrive from a connected dataset.</p>
        </motion.li>

        {/* 02 INSPECT — scan + findings */}
        <motion.li variants={lane} className="px-5 py-3">
          <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-accent">02 · Inspect</p>
          <div aria-hidden="true" className="relative mt-2.5 overflow-hidden rounded border border-border bg-canvas px-2 py-2">
            {!reduce && <span className="mkt-scan-x absolute inset-y-1 w-px bg-lime" />}
            <div className="flex gap-2">
              <motion.span className="rounded-sm border border-accent px-1.5 py-0.5 font-mono text-[10px] uppercase text-accent" {...oneShot(1.5, reduce)}>
                Email
              </motion.span>
              <motion.span className="rounded-sm border border-accent px-1.5 py-0.5 font-mono text-[10px] uppercase text-accent" {...oneShot(1.7, reduce)}>
                Phone
              </motion.span>
              <motion.span className="rounded-sm border border-border px-1.5 py-0.5 font-mono text-[10px] uppercase text-text-muted" {...oneShot(1.9, reduce)}>
                City · clear
              </motion.span>
            </div>
          </div>
          <p className="mt-2 text-[13px] text-text-muted">Sensitive columns identified — nothing changed.</p>
        </motion.li>

        {/* 03 SANITIZE — raw morphs into token */}
        <motion.li variants={lane} className="px-5 py-3">
          <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-accent">03 · Sanitize</p>
          <p className="mt-2.5 w-fit rounded border border-border bg-canvas px-2 py-1 font-mono text-xs text-text">
            email → tok_av_7f2c
          </p>
          <div className="mt-2 flex items-center gap-2 font-mono text-xs">
            <span className="text-text-muted line-through">user@example.test</span>
            <span aria-hidden="true" className="text-accent">→</span>
            <motion.span className="rounded-sm bg-accent px-1.5 py-0.5 text-accent-contrast" {...oneShot(2.0, reduce)}>
              tok_av_7f2c
            </motion.span>
          </div>
          <p className="mt-2 text-[13px] text-text-muted">An approved policy writes the transformed copy.</p>
        </motion.li>

        {/* 04 GOVERN — policy verdicts */}
        <motion.li variants={lane} className="px-5 py-3">
          <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-accent">04 · Govern</p>
          <div className="mt-2.5 grid grid-cols-2 gap-2">
            <motion.div className="rounded border border-success px-2.5 py-2" {...oneShot(2.2, reduce)}>
              <p className="font-mono text-[11px] font-bold uppercase tracking-wide text-success">Allow</p>
              <p className="mt-0.5 font-mono text-[10px] text-text-muted">budget ok · logged</p>
            </motion.div>
            <motion.div className="rounded border border-danger px-2.5 py-2" {...oneShot(2.4, reduce)}>
              <p className="font-mono text-[11px] font-bold uppercase tracking-wide text-danger">Block</p>
              <p className="mt-0.5 font-mono text-[10px] text-text-muted">PII_DIRECT · 200 data</p>
            </motion.div>
          </div>
          <p className="mt-2 text-[13px] text-text-muted">Policy decides before any provider is called.</p>
        </motion.li>

        {/* 05 VERIFY — chain links */}
        <motion.li variants={lane} className="px-5 py-3">
          <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-accent">05 · Verify</p>
          <div aria-hidden="true" className="mt-2.5 flex items-center gap-1.5">
            {[0, 1, 2].map((i) => (
              <motion.span
                key={i}
                className="flex size-7 items-center justify-center rounded border border-accent font-mono text-[10px] text-accent"
                {...oneShot(2.5 + i * 0.18, reduce)}
              >
                ✓
              </motion.span>
            ))}
            <motion.span className="ml-1 font-mono text-[11px] text-lime" {...oneShot(3.1, reduce)}>
              link 3/3 verified
            </motion.span>
          </div>
          <p className="mt-2 text-[13px] text-text-muted">Every step lands in the tamper-evident ledger.</p>
        </motion.li>
      </motion.ol>

      <figcaption className="border-t border-border px-5 py-3 font-mono text-xs leading-relaxed text-text-muted">
        Illustrative pipeline — example values are fictional; no production data is shown.
      </figcaption>
    </figure>
  );
}
