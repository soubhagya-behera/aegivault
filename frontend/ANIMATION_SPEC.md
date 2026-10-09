# Aegivault Animation & Motion Spec

> Companion to `DESIGN_SYSTEM.md`. Motion must **explain the product or guide
> attention**. Animation is never a substitute for functionality. If a motion
> does not help a user understand state, sequence, or cause-and-effect, it does
> not ship.

---

## 1. Motion library

- **Chosen library:** the project's existing Motion/Framer Motion setup if one is
  present; otherwise **one** appropriate React motion dependency, added only in
  the phase that first needs it and only with justification. (No frontend
  toolchain exists yet — see the Phase 0 report — so no motion dependency is
  added in Phase 1.)
- **Icons:** `lucide-react` for consistent interface icons, if not already
  available and justified. Icons are static by default.
- **Do not install** GSAP, Lenis, Three.js, multiple competing motion libraries,
  or heavyweight animation frameworks to implement the six allowed effects below.
  Everything here is achievable with CSS transitions/keyframes plus at most one
  lightweight motion library for the scroll-linked and spring primitives.

## 2. Timing, easing, spring defaults

Documented defaults — **not a reason to animate everything.**

| Token           | Value                              | Use                        |
| --------------- | ---------------------------------- | -------------------------- |
| Hover/focus     | ~160–200 ms (`--dur-hover` 180ms)  | control feedback           |
| Press           | ~100–140 ms (`--dur-press` 120ms)  | button/active press        |
| Standard reveal | ~350–500 ms (`--dur-reveal` 420ms) | content entering           |
| Hero sequence   | ~500–800 ms (`--dur-hero` 700ms)   | kinetic hero               |
| Layout          | ~220–320 ms (`--dur-layout` 280ms) | layout/route transitions   |

- **Primary easing:** `cubic-bezier(0.22, 1, 0.36, 1)` (`--ease-primary`) — a
  confident ease-out for entrances and state changes.
- **Spring reference:** stiffness `260`, damping `28`, mass `0.8` — for the few
  spring-driven primitives (e.g., tilt return, brand-mark settle). Use sparingly;
  most motion should use the timed easings above.
- Durations live as CSS custom properties in `tokens.css` so reduced-motion can
  collapse them in one place.

## 3. The six allowed motion patterns

Only these six. Each has a boundary.

1. **Kinetic typography on the marketing hero.** Staggered word/line reveals of
   the headline. Content is present in the DOM immediately for assistive tech and
   in reduced-motion mode; the animation only sequences visibility.
2. **Custom animated SVG Aegivault brand mark.** Original restrained
   vault/security geometry (see §6). Inline SVG, accessible name, decorative
   paths marked `aria-hidden`.
3. **Scrollytelling sequence** describing the privacy workflow (see §5). A few
   clear stages, native scrolling preserved.
4. **Restrained ambient texture / slow background displacement.** Very subtle,
   low-amplitude; never over text; disabled under reduced motion.
5. **Pointer-driven card tilt** on **selected public feature cards only** (see
   §7). Never on dashboard tables, dense surfaces, modals, or essential controls.
6. **Meaningful microinteractions** for navigation, buttons, inputs, status
   changes, skeletons, and transitions. These communicate state; they are short
   and use the timings in §2.

---

## 4. Reduced-motion behavior (mandatory)

Honor `prefers-reduced-motion: reduce` **everywhere**.

When reduced motion is requested:

- Remove large travel distances, parallax, continuous/ambient motion, pointer
  tilt, and scroll-pinned choreography.
- Replace animated reveals with **immediate or minimal** transitions (opacity
  only, no transform travel; durations collapse to ~0).
- Keep **all information and content fully accessible** — nothing is hidden
  behind an animation that will not run.
- **Never lock essential navigation or application actions behind animation.**
- `tokens.css` collapses all `--dur-*` to `0ms` and neutralizes
  animation/transition/scroll-behavior globally under the media query, so any
  future animation inherits the safe default automatically.

## 5. Scroll interaction & mobile fallbacks

- **Native smooth scrolling** via CSS `scroll-behavior: smooth`, with a
  reduced-motion override back to `auto`. Do **not** intercept native scrolling
  globally to fake a cinematic effect.
- **Scrollytelling** uses sticky positioning and progress-based transitions only
  where they improve the story (SOURCE → INSPECT → SANITIZE → GOVERN → VERIFY).
  It never pins the entire mobile page; small screens get a readable, naturally
  scrolling version with no pinned choreography.
- Any scroll-linked transform must be cheap (transform/opacity), avoid layout
  thrash, and respect reduced motion by falling back to static layout.

## 6. SVG brand-mark animation

- Design an **original** mark around restrained vault/security geometry. Inline
  SVG with an accessible name (`role="img"` + `<title>`), decorative paths marked
  `aria-hidden="true"`.
- Entrance may use a path-draw via `stroke-dasharray` / `stroke-dashoffset`
  (~`--dur-hero`), followed by a subtle completed-state transition (e.g., a
  single accent stroke settling). The **static mark must remain clear if the
  animation never runs** (SSR, reduced motion, no-JS) — the drawn and undrawn
  states are both legible.
- Keep the geometry simple; no glow, no gradient orb, no perpetual spin.

---

## 7. Pointer-tilt behavior & cleanup

A dedicated reusable component, used **only on selected public-facing feature
cards**.

- Calculate pointer position relative to the card center and map to a rotation
  clamped to approximately **±3°** (plus a very small, optional parallax on an
  inner highlight).
- **Disable** for: touch-first input (`pointer: coarse`), keyboard-only
  interaction, and `prefers-reduced-motion`. Static fallback = no tilt.
- **Reset cleanly on pointer leave** (spring return to 0 using the §2 spring
  reference, or an immediate reset under reduced motion).
- **Performance/cleanup:** attach listeners on mount and remove them on unmount;
  write transforms via a ref/`requestAnimationFrame` and **avoid React state
  updates on every pointer move**. Never tilt dashboard tables, dense data
  surfaces, modal dialogs, or essential controls.

## 8. Performance limits

- Animate only **`transform` and `opacity`** (compositor-friendly). Avoid
  animating layout properties (width/height/top/left) except tiny, bounded
  cases.
- Cap concurrent animated elements; do not animate long lists wholesale — reveal
  in bounded batches or only above the fold.
- Keep ambient/background motion low-amplitude and paused when off-screen or
  under reduced motion.
- Skeletons should suggest shape, not flash; use a single, subtle shimmer keyed
  to `--dur-layout`.
- Respect `will-change` discipline: set it only during active animation and
  release afterward.

## 9. Prohibited motion & visual clutter

- Perpetual bouncing, pulsing, or spinning with no state meaning.
- Excessive parallax; parallax on body text; parallax that fights native scroll.
- Random particle fields covering text; cursor-following decoration across the
  application.
- Animated gradients in every section; a giant decorative gradient orb.
- Scroll-pinning an entire page (especially mobile); blocking scroll.
- Motion that delays access to content or essential actions.
- Any animation that runs unchanged under `prefers-reduced-motion`.