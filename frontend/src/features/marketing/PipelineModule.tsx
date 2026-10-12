import type { MotionValue } from "motion/react";

/**
 * Compact technical readout per stage. HTML, not SVG text, so faces stay
 * legible at narrow widths. Every value is fictional; the explanation
 * panels carry the full narrative.
 */
function ModuleReadout({ index }: { index: number }) {
  switch (index) {
    case 0:
      return (
        <>
          <p className="mt-1.5 w-fit rounded border border-border bg-canvas px-1.5 py-0.5 font-mono text-[10px] text-text">customers.csv</p>
          <p className="mt-1 w-fit rounded border border-border bg-canvas px-1.5 py-0.5 font-mono text-[10px] text-text">orders_q3</p>
          <p className="mt-1.5 font-mono text-[10px] text-text-muted">3 records · untouched</p>
        </>
      );
    case 1:
      return (
        <>
          <p className="mt-1.5 flex gap-1">
            <span className="rounded-sm border border-accent px-1 py-px font-mono text-[10px] font-bold text-accent">EMAIL</span>
            <span className="rounded-sm border border-accent px-1 py-px font-mono text-[10px] font-bold text-accent">PHONE</span>
          </p>
          <p className="mt-1.5 font-mono text-[10px] text-text-muted">2 findings · 0 changed</p>
        </>
      );
    case 2:
      return (
        <>
          <p className="mt-1.5 font-mono text-[10px] text-text-muted line-through">user@example.test</p>
          <p className="mt-1 w-fit rounded-sm bg-accent px-1.5 py-0.5 font-mono text-[10px] font-bold text-accent-contrast">tok_av_7f2c</p>
          <p className="mt-1 font-mono text-[10px] text-text-muted">plan: tokenize</p>
        </>
      );
    case 3:
      return (
        <>
          <p className="mt-1.5 w-fit rounded border border-success px-1.5 py-0.5 font-mono text-[10px] font-bold text-success">ALLOW · budget ok</p>
          <p className="mt-1 w-fit rounded border border-danger px-1.5 py-0.5 font-mono text-[10px] font-bold text-danger">BLOCK · PII_DIRECT</p>
        </>
      );
    default:
      return (
        <>
          <p className="mt-1.5 flex items-center gap-1 font-mono text-[10px] text-text-muted">
            <span className="rounded border border-border px-1">k−1</span>
            <span aria-hidden="true" className="text-accent">→</span>
            <span className="rounded border border-accent px-1 text-text">k</span>
            <span aria-hidden="true" className="text-accent">→</span>
            <span className="rounded border border-border px-1">k+1</span>
          </p>
          <p className="mt-1.5 font-mono text-[10px] font-bold text-lime">link 3/3 verified</p>
        </>
      );
  }
}

const CODES = ["SOURCE", "INSPECT", "SANITIZE", "GOVERN", "VERIFY"] as const;
const NAMES = ["Source", "Inspect", "Sanitize", "Govern", "Verify"] as const;
/** Status-light tone per stage; coral is reserved for the govern risk state. */
const TONES = ["cobalt", "cobalt", "cobalt", "coral", "lime"] as const;

/** Front-face content shared by the posed module and the static fallback. */
export function ModuleFront({ index, lit }: { index: number; lit: boolean }) {
  const safe = Math.max(0, Math.min(index, 4));
  return (
    <div className="mkt-module-front">
      <div className="flex items-center justify-between gap-2">
        <p className="font-mono text-[10px] uppercase tracking-[0.14em] text-text-muted">
          {`0${safe + 1} ${CODES[safe]}`}
        </p>
        <span aria-hidden="true" data-tone={TONES[safe]} data-lit={lit} className="mkt-module-light" />
      </div>
      <p className="mt-1 font-display text-base font-semibold tracking-tight text-text">{NAMES[safe]}</p>
      <ModuleReadout index={safe} />
    </div>
  );
}

interface PipelineModuleProps {
  index: number;
  count: number;
  /** Discrete auto-active stage (lime edge + lit content). */
  current: boolean;
  /** Section scroll progress (retained for API compatibility; unused). */
  progress?: MotionValue<number>;
  /** Horizontal spacing (retained for API compatibility; unused). */
  lane?: number;
}

/**
 * Flat stage module (Chapter 03 recovery). The 3D cover-flow pose was
 * removed: text-heavy panels are never rotated out of view. Renders the
 * same front-face content statically so every stage stays legible.
 */
export default function PipelineModule({ index, current }: PipelineModuleProps) {
  return (
    <div data-active={current} className="mkt-module">
      <ModuleFront index={index} lit={current} />
    </div>
  );
}
