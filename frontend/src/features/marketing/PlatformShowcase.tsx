import { useReducedMotion } from "motion/react";
import SectionIntro from "./SectionIntro";
import Reveal from "./Reveal";
import PlatformJourney from "./PlatformJourney";
import PlatformLegacy from "./PlatformLegacy";

/**
 * #platform — capability showcase on the cool-white chapter (Chapter 02
 * rebuild).
 *
 * One manual horizontal carousel holds all six capabilities: a native
 * `overflow-x: auto` track with swipe, trackpad, drag, rail, and arrow
 * navigation. Vertical page scrolling never moves the cards — there is no
 * pinned runway, no scroll-linked transform, and Chapter 03 follows through
 * ordinary page scrolling. Reduced motion keeps the static vertical tabs
 * (`PlatformLegacy`). Palette and content are unchanged.
 */
export default function PlatformShowcase() {
  const reduce = useReducedMotion();

  return (
    <section
      id="platform"
      aria-labelledby="platform-title"
      data-mkt-chapter="platform"
      className="scroll-mt-24 border-b border-border"
    >
      <div className="mx-auto max-w-app px-6 pt-12 md:pt-20 lg:pt-28">
        <Reveal>
          <SectionIntro
            eyebrow="Chapter 02 — Platform"
            title="Six capabilities, one control plane."
            headingId="platform-title"
          >
            Each capability below is implemented in the product today. Swipe
            sideways or use the arrows to travel through all six — scrolling
            the page never moves the cards.
          </SectionIntro>
        </Reveal>
      </div>

      {reduce ? (
        <div className="mx-auto max-w-app px-6 py-8 lg:py-14">
          <PlatformLegacy />
        </div>
      ) : (
        <PlatformJourney />
      )}
    </section>
  );
}
