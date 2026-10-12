import { useRef } from "react";
import { motion, useReducedMotion, useScroll, useTransform } from "motion/react";
import { DUR, EASE } from "./motion";

/**
 * AuditChainFigure (Phase 3F): the ledger as three linked entries whose
 * verification is driven by reading progress. Scrolling the figure into view
 * traces the prev-hash connectors, sweeps a lime verify segment across the
 * chain, and lands a check on each entry in turn before the result line
 * resolves — the diagram lands its beats alongside the boundary copy, not
 * independently of it. Decorative (aria-hidden); the copy carries meaning.
 * Static and complete under reduced motion. All labels generic — no
 * fabricated hashes, counts, or customer data; the panel is labeled fictional.
 */
export default function AuditChainFigure() {
  const reduce = useReducedMotion();
  const figureRef = useRef<HTMLElement | null>(null);
  const { scrollYProgress } = useScroll({
    target: figureRef,
    offset: ["start end", "center center"],
  });

  const sweepX = useTransform(scrollYProgress, [0, 1], [0, 226]);
  const linkA = useTransform(scrollYProgress, [0.05, 0.45], [0, 1]);
  const linkB = useTransform(scrollYProgress, [0.3, 0.7], [0, 1]);
  const checkA = useTransform(scrollYProgress, [0.2, 0.4], [0, 1]);
  const checkB = useTransform(scrollYProgress, [0.45, 0.65], [0, 1]);
  const checkC = useTransform(scrollYProgress, [0.6, 0.8], [0, 1]);
  const result = useTransform(scrollYProgress, [0.75, 0.95], [0, 1]);
  const checkOpacities = [checkA, checkB, checkC];

  const entries = [
    { x: 30, tag: "k−1", label: "profile" },
    { x: 140, tag: "k", label: "sanitize" },
    { x: 250, tag: "k+1", label: "gateway" },
  ];

  return (
    <figure ref={figureRef} className="overflow-hidden rounded-xl border border-border bg-canvas p-5 shadow-[0_24px_80px_rgba(65,105,255,0.16)]">
      <div className="flex items-center justify-between gap-3">
        <p className="font-mono text-xs uppercase tracking-[0.14em] text-text-muted">
          Audit ledger — verify replay
        </p>
        <p className="rounded-sm border border-border px-2 py-0.5 font-mono text-xs uppercase tracking-wide text-text-muted">
          Fictional
        </p>
      </div>
      <svg viewBox="0 0 340 150" className="mt-4 h-auto w-full" aria-hidden="true" fill="none">
        <line x1="14" y1="70" x2="326" y2="70" stroke="var(--color-border)" strokeWidth="2" />
        {reduce ? (
          <>
            <line x1="90" y1="70" x2="140" y2="70" stroke="var(--color-accent)" strokeWidth="2.5" />
            <line x1="200" y1="70" x2="250" y2="70" stroke="var(--color-accent)" strokeWidth="2.5" />
          </>
        ) : (
          <>
            <motion.line
              x1="90" y1="70" x2="140" y2="70" stroke="var(--color-accent)" strokeWidth="2.5"
              style={{ pathLength: linkA }}
            />
            <motion.line
              x1="200" y1="70" x2="250" y2="70" stroke="var(--color-accent)" strokeWidth="2.5"
              style={{ pathLength: linkB }}
            />
            <motion.line
              x1="14" y1="70" x2="100" y2="70" stroke="var(--mkt-lime)" strokeWidth="3" strokeLinecap="round"
              style={{ x: sweepX }}
            />
          </>
        )}
        {entries.map((entry, i) => (
          <g key={entry.tag}>
            <motion.rect
              x={entry.x} y="40" width="60" height="60" rx="6"
              stroke={i === 1 ? "var(--color-accent)" : "var(--color-border-strong)"}
              strokeWidth={i === 1 ? 2.5 : 1.5}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-40px" }}
              transition={{ duration: DUR.stage, delay: 0.15 + i * 0.18, ease: EASE }}
            />
            <text x={entry.x + 10} y="64" fill="var(--color-text)" fontSize="9" fontFamily="var(--font-mono)">ENTRY</text>
            <text x={entry.x + 10} y="78" fill="var(--color-text-muted)" fontSize="9" fontFamily="var(--font-mono)">{entry.tag}</text>
            {reduce ? (
              <text x={entry.x + 10} y="92" fill="var(--mkt-lime)" fontSize="10" fontFamily="var(--font-mono)">✓</text>
            ) : (
              <motion.text x={entry.x + 10} y="92" fill="var(--mkt-lime)" fontSize="10" fontFamily="var(--font-mono)" style={{ opacity: checkOpacities[i] }}>
                ✓
              </motion.text>
            )}
          </g>
        ))}
        <text x="14" y="126" fill="var(--color-text-muted)" fontSize="10" fontFamily="var(--font-mono)">PREV-HASH LINKS · SAFE METADATA ONLY</text>
        {reduce ? (
          <text x="14" y="142" fill="var(--mkt-lime)" fontSize="10" fontFamily="var(--font-mono)">VERIFY: 3/3 LINKS HOLD — FIRST BREAK: NONE</text>
        ) : (
          <motion.text x="14" y="142" fill="var(--mkt-lime)" fontSize="10" fontFamily="var(--font-mono)" style={{ opacity: result }}>
            VERIFY: 3/3 LINKS HOLD — FIRST BREAK: NONE
          </motion.text>
        )}
      </svg>
      <figcaption className="mt-3 font-mono text-xs leading-relaxed text-text-muted">
        Schematic — verification replays the chain and reports the first broken link.
      </figcaption>
    </figure>
  );
}
