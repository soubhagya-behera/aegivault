import { motion, useReducedMotion } from "motion/react";
import SectionIntro from "./SectionIntro";
import Reveal from "./Reveal";
import AuditChainFigure from "./AuditChainFigure";
import { VIEWPORT_ONCE, staggerChild, staggerContainer } from "./motion";

interface Boundary {
  index: string;
  title: string;
  body: string;
}

const BOUNDARIES: readonly Boundary[] = [
  {
    index: "01",
    title: "Owner-scoped resources",
    body: "Datasets, policies, runs, and bindings are scoped to their owner and enforced by the backend on every request. Hiding a control in the interface is never treated as authorization.",
  },
  {
    index: "02",
    title: "Inspection before AI release",
    body: "Outbound requests and provider responses are inspected for PII and secret patterns before release. The detectors are pattern-based — they cover known formats and labelled values, and do not claim to catch every possible secret.",
  },
  {
    index: "03",
    title: "Policy-controlled transformation and limits",
    body: "Only an explicit policy decides what a sanitization run changes. Gateway request limits and enforced token budgets bound what any actor can send, and quota decisions are recorded as policy outcomes, not security verdicts.",
  },
  {
    index: "04",
    title: "Tamper-evident audit evidence",
    body: "A cryptographically linked ledger records safe metadata for every detection, sanitization, and gateway decision, and a verification replay detects modification. This is tamper-evidence — not tamper-proofing, and not a blockchain.",
  },
];

/**
 * #security (Phase 3E): high-contrast graphite evidence chapter. Intro and
 * the traveling audit-chain figure on the left; the four implemented control
 * boundaries as bold divider rows on the right with a lime index treatment.
 * Static and complete under reduced motion.
 */
export default function SecuritySection() {
  const reduce = useReducedMotion();

  return (
    <section
      id="security"
      aria-labelledby="security-title"
      data-mkt-chapter="evidence"
      className="scroll-mt-24 border-b border-border"
    >
      <div className="mx-auto max-w-app px-6 py-16 md:py-20 lg:py-28">
        <div className="grid grid-cols-1 gap-10 lg:grid-cols-12 lg:gap-8">
          <Reveal className="lg:col-span-5">
            <SectionIntro
              eyebrow="Chapter 04 — Evidence"
              title="Control boundaries, stated plainly."
              headingId="security-title"
            >
              These are implemented controls, not certifications. Aegivault
              claims no external compliance certification, and no single
              control replaces careful review of your own data and policies.
            </SectionIntro>
            <div className="mt-8">
              <AuditChainFigure />
            </div>
            <p className="mt-6 border-l-2 border-lime pl-4 text-sm leading-relaxed text-text-muted">
              Honest boundaries are part of the product: a blocked request, a
              refused quota, and a failed verification each say exactly what
              happened — no more.
            </p>
          </Reveal>

          <motion.ul
            className="border-t border-border lg:col-span-6 lg:col-start-7"
            variants={staggerContainer(0.08, 0.05)}
            initial={reduce ? false : "hidden"}
            whileInView="visible"
            viewport={VIEWPORT_ONCE}
          >
            {BOUNDARIES.map((boundary) => (
              <motion.li
                key={boundary.index}
                variants={staggerChild}
                className="group grid grid-cols-[3rem_1fr] gap-4 border-b border-border py-6 transition-colors duration-200 hover:border-border-strong"
              >
                <span className="mt-1 font-display text-lg font-bold text-lime">{boundary.index}</span>
                <div className="min-w-0">
                  <h3 className="font-display text-lg font-semibold tracking-tight text-text sm:text-xl">
                    {boundary.title}
                  </h3>
                  <p className="mt-1.5 text-[15px] leading-relaxed text-text-muted">{boundary.body}</p>
                </div>
              </motion.li>
            ))}
          </motion.ul>
        </div>
      </div>
    </section>
  );
}
