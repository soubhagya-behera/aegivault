import { motion, useReducedMotion } from "motion/react";

const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1];

interface PipelineStage {
  index: string;
  name: string;
  detail: string;
  /** Fictional example shown to make the step concrete — never real data. */
  example?: string;
}

const STAGES: readonly PipelineStage[] = [
  { index: "01", name: "Source", detail: "Rows arrive from a connected dataset." },
  {
    index: "02",
    name: "Inspect",
    detail: "Fields are classified and sensitive columns identified.",
  },
  {
    index: "03",
    name: "Sanitize",
    detail: "Approved transformations replace raw values.",
    example: "email → tok_av_7f2c",
  },
  {
    index: "04",
    name: "Govern",
    detail: "Policy decides what an AI-bound request may receive.",
  },
  {
    index: "05",
    name: "Verify",
    detail: "Each step is recorded in the tamper-evident audit ledger.",
  },
];

/**
 * Security pipeline illustration: SOURCE → INSPECT → SANITIZE → GOVERN →
 * VERIFY, composed as a bone-paper technical spec sheet rather than a grid of
 * icon cards. An ordered list carries the meaning (readable by assistive tech
 * and at every width); the rail, nodes, and one-time oxide draw are decorative.
 *
 * Motion: the oxide rail draws downward once on mount to suggest flow. Under
 * `prefers-reduced-motion` the rail is simply present, fully drawn. All example
 * values are explicitly labeled fictional in the caption.
 */
export default function SecurityPipelineIllustration() {
  const reduce = useReducedMotion();

  return (
    <figure className="overflow-hidden rounded-lg bg-surface-paper text-text-inverse">
      <div className="flex items-center justify-between gap-3 border-b border-text-inverse/10 px-5 py-3">
        <span className="font-mono text-xs uppercase tracking-wide text-text-inverse">
          Security pipeline
        </span>
        <span className="rounded-sm border border-text-inverse/20 px-2 py-0.5 font-mono text-xs uppercase tracking-wide text-text-inverse">
          Illustrative
        </span>
      </div>

      <ol className="relative px-5">
        {/* Static rail behind the nodes… */}
        <span
          aria-hidden="true"
          className="absolute bottom-7 left-7 top-7 w-px bg-text-inverse/15"
        />
        {/* …with a single oxide pass that draws the flow once on mount. */}
        <motion.span
          aria-hidden="true"
          className="absolute bottom-7 left-7 top-7 w-px origin-top bg-accent"
          {...(reduce
            ? {}
            : {
                initial: { scaleY: 0 },
                animate: { scaleY: 1 },
                transition: { duration: 0.8, delay: 0.2, ease: EASE },
              })}
        />

        {STAGES.map((stage, index) => {
          const isLast = index === STAGES.length - 1;
          return (
            <li
              key={stage.index}
              className="grid grid-cols-[1rem_2.25rem_1fr] items-start gap-x-3 border-b border-text-inverse/10 py-4 last:border-b-0 sm:gap-x-4"
            >
              <span
                aria-hidden="true"
                className={
                  isLast
                    ? "mt-1.5 size-3 rotate-45 border border-accent bg-accent"
                    : "mt-1.5 size-3 rotate-45 border border-text-inverse/60 bg-surface-paper"
                }
              />
              <span className="mt-0.5 font-mono text-xs text-text-inverse/70">
                {stage.index}
              </span>
              <div className="min-w-0">
                <p className="font-display text-base font-semibold uppercase tracking-wide text-text-inverse">
                  {stage.name}
                </p>
                <p className="mt-1 text-sm leading-snug text-text-inverse/70">
                  {stage.detail}
                </p>
                {stage.example === undefined ? null : (
                  <p className="mt-2 w-fit rounded-sm border border-text-inverse/15 bg-text-inverse/5 px-2 py-1 font-mono text-xs text-text-inverse">
                    {stage.example}
                  </p>
                )}
              </div>
            </li>
          );
        })}
      </ol>

      <figcaption className="border-t border-text-inverse/10 px-5 py-3 font-mono text-xs leading-relaxed text-text-inverse/70">
        Illustrative pipeline — example values are fictional; no production data
        is shown.
      </figcaption>
    </figure>
  );
}
