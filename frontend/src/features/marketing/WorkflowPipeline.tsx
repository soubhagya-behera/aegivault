import { motion, useReducedMotion, useTransform, type MotionValue } from "motion/react";
import { ModuleFront } from "./PipelineModule";

interface WorkflowPipelineProps {
  /** Discrete auto-active stage; negative shows every module lit, none current. */
  active: number;
  /** Section scroll progress 0→1 (stable MotionValue, never re-renders). */
  progress: MotionValue<number>;
  /** Compact scene for narrow widths (tighter spacing). */
  compact?: boolean;
}

/**
 * Data-flow spine shared by the desktop schematic and the mobile journey:
 * cobalt route, five stage nodes (lime = current), and a progress-driven
 * packet. Horizontal by default; `vertical` renders a top-to-bottom spine
 * whose packet travels downward for the mobile journey. MotionValues only —
 * zero per-frame React state — so scrolling backward reverses symmetrically.
 * Decorative (`aria-hidden`); surrounding text carries the narrative.
 */
export function WorkflowSpine({ active, progress, vertical = false }: { active: number; progress: MotionValue<number>; vertical?: boolean }) {
  const reduce = useReducedMotion();
  const safe = Math.max(0, Math.min(active, 4));
  const noneCurrent = active < 0;
  const packetLeft = useTransform(progress, [0, 1], ["10%", "90%"]);
  const packetTop = useTransform(progress, [0, 1], ["10%", "90%"]);
  const routeFill = useTransform(progress, [0, 1], [0, 1]);

  if (vertical) {
    return (
      <div className="mkt-pipe-vertical" aria-hidden="true">
        <svg viewBox="0 0 24 100" preserveAspectRatio="none" className="mkt-pipe-route-vertical" fill="none">
          <line x1="12" y1="6" x2="12" y2="94" stroke="var(--color-border)" strokeWidth="1.4" />
          {reduce ? (
            <line x1="12" y1="6" x2="12" y2="94" stroke="var(--color-accent)" strokeWidth="1.8" />
          ) : (
            <motion.line
              x1="12" y1="6" x2="12" y2="94"
              stroke="var(--color-accent)" strokeWidth="1.8"
              style={{ scaleY: routeFill, transformOrigin: "12px 6px" }}
            />
          )}
          {[0, 1, 2, 3, 4].map((i) => {
            const lit = noneCurrent || i <= safe;
            const current = !noneCurrent && i === safe;
            const cy = 10 + i * 20;
            return (
              <rect
                key={i}
                x={12 - 1.8} y={cy - 1.8} width="3.6" height="3.6"
                transform={`rotate(45 12 ${cy})`}
                fill={current ? "var(--mkt-lime)" : lit ? "var(--color-accent)" : "var(--color-border-strong)"}
                stroke="none"
              />
            );
          })}
        </svg>
        {reduce ? null : <motion.div className="mkt-pipe-packet-vertical" style={{ top: packetTop }} />}
        {reduce ? <div className="mkt-pipe-packet-vertical" style={{ top: "90%" }} /> : null}
      </div>
    );
  }

  return (
    <div className="mkt-pipe" aria-hidden="true">
      <svg viewBox="0 0 100 24" preserveAspectRatio="none" className="mkt-pipe-route" fill="none">
        <line x1="4" y1="12" x2="96" y2="12" stroke="var(--color-border)" strokeWidth="1.4" />
        {reduce ? (
          <line x1="4" y1="12" x2="96" y2="12" stroke="var(--color-accent)" strokeWidth="1.8" />
        ) : (
          <motion.line
            x1="4" y1="12" x2="96" y2="12"
            stroke="var(--color-accent)" strokeWidth="1.8"
            style={{ scaleX: routeFill, transformOrigin: "4px 12px" }}
          />
        )}
        {[0, 1, 2, 3, 4].map((i) => {
          const lit = noneCurrent || i <= safe;
          const current = !noneCurrent && i === safe;
          const cx = 10 + i * 20;
          return (
            <rect
              key={i}
              x={cx - 1.8} y={12 - 1.8} width="3.6" height="3.6"
              transform={`rotate(45 ${cx} 12)`}
              fill={current ? "var(--mkt-lime)" : lit ? "var(--color-accent)" : "var(--color-border-strong)"}
              stroke="none"
            />
          );
        })}
      </svg>
      {reduce ? null : <motion.div className="mkt-pipe-packet" style={{ left: packetLeft }} />}
      {reduce ? <div className="mkt-pipe-packet" style={{ left: "90%" }} /> : null}
    </div>
  );
}

/**
 * Flat workflow schematic (Chapter 03 recovery): a readable data-flow spine
 * with five stage nodes plus a stack of five legible stage cards. No 3D
 * travel — every stage keeps its technical readout visible at all times and
 * the active stage is highlighted with lime. Scroll progress drives only the
 * route fill and packet position (MotionValues, zero per-frame React state),
 * so backward scrolling reverses symmetrically. Decorative (`aria-hidden`
 * scene); the stage list / explanation carries the narrative. All values
 * fictional.
 */
export default function WorkflowPipeline({ active, progress, compact = false }: WorkflowPipelineProps) {
  const noneCurrent = active < 0;
  const safe = Math.max(0, Math.min(active, 4));

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-canvas p-5 shadow-[0_24px_80px_rgba(65,105,255,0.18)]">
      <div className="flex items-center justify-between gap-3">
        <p className="font-mono text-xs uppercase tracking-[0.14em] text-text-muted">Workflow schematic</p>
        <p className="flex gap-2">
          <span className="rounded-sm border border-border px-2 py-0.5 font-mono text-xs uppercase tracking-wide text-text-muted">Fictional</span>
          <span className="rounded-sm bg-accent px-2 py-0.5 font-mono text-xs font-bold uppercase tracking-wide text-accent-contrast">
            {noneCurrent ? "Stages 1–5" : `Stage ${safe + 1} / 5`}
          </span>
        </p>
      </div>

      <WorkflowSpine active={active} progress={progress} />
      <div className={`mkt-pipe-stage${compact ? " mkt-pipe-compact" : ""}`}>
        {[0, 1, 2, 3, 4].map((i) => (
          <div
            key={i}
            data-active={!noneCurrent && i === safe}
            className="mkt-module"
          >
            <ModuleFront index={i} lit={noneCurrent || i <= safe} />
          </div>
        ))}
      </div>

      <p className="mt-2 font-mono text-xs leading-relaxed text-text-muted">Schematic — any values shown are fictional.</p>
    </div>
  );
}
