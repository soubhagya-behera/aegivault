import type { Variants } from "motion/react";
import { useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";

/**
 * Shared motion language (Phase 3C).
 *
 * Single source for the documented primary easing
 * (`cubic-bezier(0.22, 1, 0.36, 1)`, ANIMATION_SPEC §2) and the bounded
 * durations: interactions ~160–250ms, section reveals ~350–500ms, the
 * feature-selector panel ~200–300ms. Components read these instead of
 * scattering their own easing/duration values.
 */

/** Primary easing — mirrors `--ease-primary` in tokens.css. */
export const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1];

/** Bounded durations in seconds. */
export const DUR = {
  /** Press feedback (~--dur-press). */
  press: 0.12,
  /** Hover/focus feedback (~--dur-hover). */
  hover: 0.18,
  /** Feature-selector panel swap (200–300ms budget). */
  panel: 0.24,
  /** Standard scroll reveal (~--dur-reveal). */
  reveal: 0.42,
  /** Hero illustration settle (~--dur-hero). */
  hero: 0.55,
  /** One-shot progress/rail draws. */
  rail: 0.8,
  /** Sticky stage + diagram swaps (Phase 3D, ~--dur-stage). */
  stage: 0.3,
} as const;

/**
 * Shared scroll-trigger viewport: trigger once when the block approaches the
 * viewport, and never replay on scroll up/down.
 */
export const VIEWPORT_ONCE = { once: true, margin: "-80px" } as const;

/** Staggered container for short scroll-reveal batches. */
export function staggerContainer(stagger = 0.08, delay = 0.05): Variants {
  return {
    hidden: {},
    visible: { transition: { staggerChildren: stagger, delayChildren: delay } },
  };
}

/** Single child inside a staggered container: short fade, small rise. */
export const staggerChild: Variants = {
  hidden: { opacity: 0, y: 10 },
  visible: { opacity: 1, y: 0, transition: { duration: DUR.reveal, ease: EASE } },
};

/**
 * One-shot oxide stroke draw for selective SVG accents. Returns empty props
 * under reduced motion so the stroke renders complete and static.
 */
export function useStrokeDraw(delay = 0) {
  const reduce = useReducedMotion();
  if (reduce) return {};
  return {
    initial: { opacity: 0, pathLength: 0 },
    animate: { opacity: 1, pathLength: 1 },
    transition: { duration: 0.3, delay, ease: EASE },
  };
}

/** One-shot opacity fade for small SVG accents (arrowheads, tags). */
export function useAccentFade(delay = 0) {
  const reduce = useReducedMotion();
  if (reduce) return {};
  return {
    initial: { opacity: 0 },
    animate: { opacity: 1 },
    transition: { duration: 0.25, delay, ease: EASE },
  };
}

/* ---------------------------------------------------------------------------
 * Phase 3F scroll-journey helpers (pure — tested in journey.test.ts).
 * ------------------------------------------------------------------------- */

/**
 * Discrete panel index for a 0→1 pinned-scroll progress. Clamped, so
 * overscroll never escapes the track. Slices the journey into `count` equal
 * bands: progress 0 sits on panel 0, progress 1 sits on the last panel.
 */
export function indexForProgress(progress: number, count: number): number {
  if (count <= 0) return 0;
  const clamped = Math.min(1, Math.max(0, progress));
  return Math.min(count - 1, Math.floor(clamped * count));
}

/**
 * Scroll progress 0→1 that brings panel `index` into view inside a pinned
 * journey. Used to convert a marker click back into a vertical scroll
 * target: `top + progressForIndex(i) * scrollDistance`.
 */
export function progressForIndex(index: number, count: number): number {
  if (count <= 1) return 0;
  return Math.min(count - 1, Math.max(0, index)) / (count - 1);
}

/**
 * Manual-carousel active index (Chapter 02 rebuild): given the horizontal
 * `scrollLeft` of the native track, the measured start offset of every card,
 * and the track's maximum scroll offset, returns the card nearest the
 * track's leading edge. Pure and clamped, so overscroll never escapes
 * 0..count-1. Used by the carousel's scroll listener only — vertical page
 * scroll never feeds this function.
 *
 * The `maxScroll` rule is geometric, not a fudge: the last card's start is
 * unreachable whenever trailing padding/gap keeps
 * `maxScroll < starts[last]`, so nearest-start alone can never select Audit
 * at the far-right end — it would keep returning Limits. When the track is
 * scrolled fully to its maximum (trailing edge revealed), the last card is
 * by definition the terminal visible content and wins. A few px of tolerance
 * cover sub-pixel settling; the window is far smaller than any card stride,
 * so mid-track selection is unaffected.
 */
export function carouselIndexForScroll(
  starts: readonly number[],
  scrollLeft: number,
  maxScroll?: number,
): number {
  if (starts.length === 0) return 0;
  if (maxScroll !== undefined && maxScroll > 0 && scrollLeft >= maxScroll - 4) {
    return starts.length - 1;
  }
  let best = 0;
  let bestDist = Math.abs((starts[0] ?? 0) - scrollLeft);
  for (let i = 1; i < starts.length; i++) {
    const dist = Math.abs((starts[i] ?? 0) - scrollLeft);
    if (dist < bestDist) {
      best = i;
      bestDist = dist;
    }
  }
  return best;
}

/**
 * Stage index for a 3D pipeline journey (Phase 3I.1): rounds to the nearest
 * module center instead of flooring into bands, so the rail label, header
 * chip, and explanation always agree with the module currently in focus.
 * Clamped — overscroll never escapes the sequence.
 */
export function stageForProgress(progress: number, count: number): number {
  if (count <= 0) return 0;
  return Math.min(count - 1, Math.max(0, Math.round(progress * (count - 1))));
}

/**
 * Authoritative workflow stage selection (Chapter 03 sync fix): given the
 * vertical centers of the currently visible stage panels and the viewport
 * center, returns the position of the panel closest to the viewport center.
 * Pure — the section feeds it real panel rects on scroll/resize, so the
 * schematic, counter, rail, and explanation always name the stage the
 * reader is actually looking at, forward and back. Ties resolve to the
 * earlier stage; empty input returns 0. Unit-tested in
 * workflow-sync.test.tsx.
 */
export function nearestCenterIndex(centers: readonly number[], viewportCenter: number): number {
  if (centers.length === 0) return 0;
  let best = 0;
  let bestDist = Math.abs((centers[0] ?? viewportCenter) - viewportCenter);
  for (let i = 1; i < centers.length; i++) {
    const dist = Math.abs((centers[i] ?? viewportCenter) - viewportCenter);
    if (dist < bestDist) {
      best = i;
      bestDist = dist;
    }
  }
  return best;
}

/**
 * Responsive switch for journeys that differ below the desktop breakpoint
 * (`lg`). Listens for changes (window resize, orientation change, desktop
 * `matchMedia` is unavailable — defaults to the desktop layout.
 */
export function useIsMobile(breakpoint = 1024): boolean {
  const query = `(max-width: ${breakpoint - 0.5}px)`;
  const [mobile, setMobile] = useState<boolean>(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const list = window.matchMedia(query);
    const update = () => setMobile(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);

  return mobile;
}
