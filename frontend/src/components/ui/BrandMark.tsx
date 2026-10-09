import { motion, useReducedMotion } from "motion/react";

const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1];

interface BrandMarkProps {
  /** Rendered box size, e.g. `"size-7"`. Color follows `currentColor`. */
  className?: string;
  /**
   * Accessible name for standalone use. Provide it only when the mark appears
   * on its own; next to the visible wordmark inside a link, omit it so the SVG
   * stays decorative and the name is not announced twice.
   */
  title?: string;
}

/**
 * Original Aegivault brand mark: restrained vault geometry — a rounded vault
 * door frame, a dial, and a single oxide dial pointer that settles last.
 *
 * The static (undrawn) state is fully legible: when animation is disabled,
 * reduced motion is requested, or JS never runs, the paths render complete.
 * Decorative paths are `aria-hidden`; the accessible name comes from `title`
 * (standalone) or the surrounding link text (in navigation).
 */
export default function BrandMark({ className = "size-7", title }: BrandMarkProps) {
  const reduce = useReducedMotion();

  function draw(delay: number) {
    if (reduce) return {};
    return {
      initial: { pathLength: 0 },
      animate: { pathLength: 1 },
      transition: { duration: 0.45, delay, ease: EASE },
    };
  }

  return (
    <svg
      viewBox="0 0 32 32"
      fill="none"
      className={className}
      role={title === undefined ? undefined : "img"}
      aria-hidden={title === undefined ? true : undefined}
    >
      {title === undefined ? null : <title>{title}</title>}
      {/* Vault door frame */}
      <motion.path
        d="M 10 3.5 H 22 A 6.5 6.5 0 0 1 28.5 10 V 22 A 6.5 6.5 0 0 1 22 28.5 H 10 A 6.5 6.5 0 0 1 3.5 22 V 10 A 6.5 6.5 0 0 1 10 3.5 Z"
        stroke="currentColor"
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        {...draw(0)}
      />
      {/* Vault dial */}
      <motion.path
        d="M 10 16 A 6 6 0 0 1 22 16 A 6 6 0 0 1 10 16 Z"
        stroke="currentColor"
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        {...draw(0.15)}
      />
      {/* Oxide dial pointer — the accent stroke settles last. */}
      <motion.path
        d="M 16 16 V 11"
        stroke="var(--color-accent)"
        strokeWidth={2}
        strokeLinecap="round"
        aria-hidden="true"
        {...(reduce
          ? {}
          : {
              initial: { opacity: 0, pathLength: 0 },
              animate: { opacity: 1, pathLength: 1 },
              transition: { duration: 0.25, delay: 0.45, ease: EASE },
            })}
      />
    </svg>
  );
}
