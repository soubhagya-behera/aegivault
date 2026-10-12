import type { ReactNode } from "react";
import { motion, useReducedMotion } from "motion/react";
import { DUR, EASE, VIEWPORT_ONCE } from "./motion";

interface RevealProps {
  children: ReactNode;
  className?: string;
  /** Extra delay before the reveal starts; keep small and rare. */
  delay?: number;
  /** Vertical travel in px; small by design, collapses under reduced motion. */
  y?: number;
}

/**
 * Scroll-triggered section reveal (Phase 3C): short opacity fade with a
 * small vertical offset, triggered once on viewport entry. Under reduced
 * motion (or in the reduced-motion test stand-in) it renders a plain static
 * wrapper so content is never hidden behind an animation.
 */
export default function Reveal({ children, className, delay = 0, y = 12 }: RevealProps) {
  const reduce = useReducedMotion();

  if (reduce) {
    return <div className={className}>{children}</div>;
  }

  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={VIEWPORT_ONCE}
      transition={{ duration: DUR.reveal, delay, ease: EASE }}
    >
      {children}
    </motion.div>
  );
}
