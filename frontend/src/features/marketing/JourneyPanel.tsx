import { memo } from "react";
import type { PlatformFeature } from "./platformData";
import PlatformPanelContent from "./PlatformPanelContent";

interface JourneyPanelProps {
  feature: PlatformFeature;
  index: number;
  count: number;
  active: boolean;
}

/**
 * One manual-carousel card (Chapter 02 rebuild).
 *
 * Static content only — no scroll-linked transforms, opacity fades, or 3D
 * pose. The full capability (number, title, description, points, schematic,
 * fictional-value caption) renders through the shared
 * `PlatformPanelContent`, so carousel cards and the reduced-motion tabs can
 * never drift apart. The parent carousel moves this card by scrolling the
 * native track; the card itself never translates.
 */
function JourneyPanelInner({ feature, index, count, active }: JourneyPanelProps) {
  return (
    <article
      aria-label={`Capability ${feature.index}: ${feature.title}`}
      aria-roledescription="slide"
      aria-current={active ? "true" : undefined}
      data-active={active ? "true" : "false"}
      tabIndex={0}
      className={`mkt-carousel-card rounded-xl border bg-surface p-4 focus:outline-none sm:p-5 md:p-6 ${
        active
          ? "border-accent shadow-[0_24px_70px_rgba(20,26,38,0.16)]"
          : "border-border"
      }`}
    >
      <p className="sr-only">
        Card {index + 1} of {count}
      </p>
      <PlatformPanelContent feature={feature} density="compact" />
    </article>
  );
}

const JourneyPanel = memo(JourneyPanelInner);
export default JourneyPanel;
