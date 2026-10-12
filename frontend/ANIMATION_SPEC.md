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
| Chapter         | ~500 ms (`--dur-chapter`)          | marketing chapter bg/fg transitions |
| Stage swap      | ~300 ms (`--dur-stage`)            | sticky stage + diagram swaps |

- **Primary easing:** `cubic-bezier(0.22, 1, 0.36, 1)` (`--ease-primary`) — a
  confident ease-out for entrances and state changes.
- **Spring reference:** stiffness `260`, damping `28`, mass `0.8` — for the few
  spring-driven primitives (e.g., tilt return, brand-mark settle). Use sparingly;
  most motion should use the timed easings above.
- Durations live as CSS custom properties in `tokens.css` so reduced-motion can
  collapse them in one place.

### 2b. Phase 3E motion language (marketing chapters)

- **Opening sequence (once on mount):** backdrop glow settles → eyebrow →
  headline lines rise through overflow masks → lead → lime CTA + ghost →
  live-pipeline lanes boot in order (records drift, findings badge, token
  morphs, verdicts resolve, chain checks). Content is in the DOM immediately;
  motion only sequences visibility, never replays on state updates.
- **Chapter transitions:** each `data-mkt-chapter` owns its bg/fg; CSS
  `transition: background-color/color var(--dur-chapter)` makes graphite →
  ice → midnight → graphite → cobalt read as deliberate chapters with no
  scroll listeners or per-frame React state.
- **Workflow choreography:** desktop pins `WorkflowVisual` while five 52vh
  stages scroll past. `useScroll` + `useSpring` fill the spine (MotionValue,
  zero re-renders); one `IntersectionObserver` sets the discrete active
  stage (lime node, scene crossfade, caption swap, `aria-current`). Mobile
  stacks the schematic above compact stages with no pinning.
- **Shared-layout rules:** `platform-active-rule` (cobalt rail) and
  `workflow-active-glow` (lime bar) glide on selection/stage change; panels
  swap via `AnimatePresence mode="wait"` with rise + settle.
- **Technical SVG motion:** chip drift, scan sweeps, dash-crawl connectors,
  token morphs, verdict resolution, traveling verify pass — each demonstrates
  a capability; opacity pulses are never the primary animation.
- **Ambient (quiet only):** 24s backdrop drift, all inside visual panels or
  backdrops, disabled under reduced motion, calmed on mobile. No particles,
  no WebGL, no parallax on body text.
- **Performance boundaries:** MotionValues for scroll-linked fills;
  transform/opacity/SVG-stroke only; single observer disconnected on unmount.

### 2c. Phase 3F scroll-driven product experience (marketing chapters)

- **Platform manual carousel (Chapter 02):** six capability cards live in one
  native horizontal `overflow-x: auto` track (`PlatformJourney`). Vertical
  page scrolling never moves the cards — there is no `useScroll`,
  `useTransform`, `window.scrollY` reader, or pinned runway. Horizontal
  movement comes only from deliberate interaction: touch swipe, native
  horizontal scroll, pointer drag, the capability rail, or the previous/next
  buttons. Navigation scrolls the track container only (`track.scrollTo`),
  never `scrollIntoView`, so the document never shifts sideways. The active
  index derives from the track's real `scrollLeft` vs. card `offsetLeft`
  (with a max-scroll rule so Audit wins at the far end), and drives the rail
  highlight, counter, progress bar, and button boundaries. Reduced motion
  falls back to the static vertical tab explorer (`PlatformLegacy`). The
  active-index math is unit-tested (`journey.test.tsx`).
- **Transition fix:** panel/scene swaps use `AnimatePresence mode="popLayout"`
  (overlapping crossfade) instead of `mode="wait"` (unmount gap), so rapid
  selection never passes through a blank frame.
- **Workflow additions:** a lime data packet rides the spine with journey
  progress; the schematic header carries a discrete `Stage n / 5` counter;
  VERIFY lands a `SEQUENCE COMPLETE` tag with the audit-chain result.
- **Hero exit:** the pipeline panel alone drifts, settles, and softens as the
  visitor scrolls into Platform; the heading never moves.
- **Evidence:** the audit figure's connectors trace, verify sweep travels, and
  entry checks + result resolve with section-entry scroll progress
  (`start end` → `center center`), coordinated with the boundary copy.
- **Conversion close:** a lime rule draws on entry; the outline wordmark
  parallaxes with chapter scroll. CTAs stay static and usable throughout.

### 2d. Phase 3G mobile folding cards (below the `lg` breakpoint)

- **Workflow folding card:** a five-stage rail over the shared `WorkflowVisual`
  with the stage explanation appended inline via a `trailing` slot. Tapping a
  stage glides the data packet along the spine (`animate` on the progress
  MotionValue) while the explanation folds left-to-right. Desktop passes no
  `trailing` node, so its output is unchanged.
- Both keep native buttons (≥44px), roving arrow-key travel, `aria-current`,
  and visible focus; focus is never moved into the swapping panel, and the
  panel container never unmounts. Reduced motion renders instant swaps with
  full content. No `wheel`/scroll interception; rails scroll natively inside
  their padded containers so the page never overflows horizontally.

### 2e. Chapter 02 manual carousel (below the `lg` breakpoint)

- **One manual carousel:** `PlatformJourney` serves every motion-safe viewport
  with the same manual horizontal track described in §2c. Below `lg`, cards
  span the container width (`width: 100%`), so one complete capability is
  fully visible at a time; touch swipe and native horizontal scrolling move
  the track, and the rail/previous/next controls scroll it container-locally.
  Vertical scrolling moves only the document — never the cards. Compact
  mobile copy (a concise `tagline` per capability) keeps cards short; the
  full technical description renders at `sm:` and above. Reduced motion
  renders the static vertical tab explorer with no pinning.

### 2f. Phase 3I isometric workflow pipeline (Chapter 03)

- **Scene:** five cuboid modules (front face + lit top face + recessed
  chassis layer, directional inset lighting, restrained shadows) riding a
  cobalt route inside a `perspective` / `preserve-3d` scene. Lime marks the
  auto-active module (edge + top strip + lit readout); coral is reserved for
  the govern BLOCK pill. No WebGL, no new dependency.
- **Scroll-driven 3D:** pose is a pure function of journey progress —
  `d = index − progress·(count−1)` drives `x` (conveyor travel), `rotateY`
  (cover-flow ±42° max, facing at focus), `translateZ` (forward at focus,
  receding with distance), sink, scale, and opacity. MotionValues only, zero
  per-frame React state; backward scroll reverses symmetrically. The route
  fills with progress, node diamonds ignite discretely, and a lime packet
  with a cobalt halo marks the processing point.
- **Desktop:** the working sticky + stage-list structure is preserved; only
  the flat schematic is replaced by the pipeline (same `active` + spring
  `progress` inputs, same observer driver).
- **Mobile:** a measured pinned journey (pin height + 0.85 pin-heights per
  stage) auto-advances all five stages; rail + explanation stay in sync and
  the pin releases into the next chapter. Rail taps select without moving the
  page. Reduced motion renders a stable module stack with instant swaps,
  never pinned.

### 2f.1. Phase 3I.1 mobile rendering repair (Chapter 03)

- **Root cause — missing containing block:** `.mkt-module` had no `position`,
  so its absolute chassis layer (`::after`, scene-sized inset) and top face
  anchored to `.mkt-pipe` instead — five overlapping scene-sized empty
  outlines veiling the real fronts, detached strips at the scene top, and a
  void-looking stage. Fixed with `position: relative` on the module; no
  architecture change.
- **Label sync:** the rail/header/explanation now use `stageForProgress`
  (rounds to the nearest module center, unit-tested) instead of floor bands,
  so the label always names the module in focus; poses and labels derive from
  the single scroll progress value.
- **Overlap tuning:** lanes exceed module widths (168 mobile / 185 desktop),
  max facing ±36°, and neighbors dim faster — peeks read as depth without
  hiding the focused front. Far modules exit the clipped scene fully.

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