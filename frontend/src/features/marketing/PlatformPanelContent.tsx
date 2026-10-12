import FeatureDiagram from "./FeatureDiagram";
import type { PlatformFeature } from "./platformData";

/**
 * Shared capability scene body (Phase 3F): kicker, title, explanation,
 * supporting points, and the bespoke technical schematic. Rendered by both
 * the static tab panel (mobile / reduced motion) and the pinned journey
 * cards (desktop, `compact` so the pinned viewport fits shorter screens),
 * so the two never drift apart. Values are fictional.
 */
export default function PlatformPanelContent({
  feature,
  density = "regular",
}: {
  feature: PlatformFeature;
  density?: "regular" | "compact";
}) {
  const compact = density === "compact";
  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <p className="rounded-sm bg-accent px-2 py-1 font-mono text-[11px] font-bold uppercase tracking-[0.12em] text-accent-contrast">
          Capability {feature.index}
        </p>
        <p className="font-mono text-xs uppercase tracking-[0.12em] text-accent">{feature.code}</p>
      </div>
      <h3
        className={`font-display font-semibold tracking-tight text-text ${
          compact ? "mt-2 text-lg sm:text-2xl" : "mt-4 text-2xl sm:text-3xl"
        }`}
      >
        {feature.title}
      </h3>
      {/* Mobile: one concise tagline keeps the card short. Desktop keeps the
          full technical explanation (`sm:` breakpoint). */}
      <p className="mt-1.5 max-w-prose text-sm leading-relaxed text-text-muted sm:hidden">{feature.tagline}</p>
      <p
        className={`hidden max-w-prose leading-relaxed text-text-muted sm:block ${
          compact ? "mt-2.5 text-[15px]" : "mt-3 text-base"
        }`}
      >
        {feature.description}
      </p>
      <ul className={`grid gap-2 sm:grid-cols-2 ${compact ? "mt-3" : "mt-5"}`}>
        {feature.points.map((point) => (
          <li key={point} className={`flex items-start gap-2.5 rounded-md border border-border bg-canvas/50 text-text ${compact ? "px-2.5 py-2 text-[13px] font-medium" : "px-3 py-2.5 text-sm font-medium"}`}>
            <span aria-hidden="true" className="mt-1.5 size-2 shrink-0 rotate-45 bg-accent" />
            {point}
          </li>
        ))}
      </ul>
      <figure className={`rounded-lg border-2 border-accent/25 bg-canvas/40 ${compact ? "mt-3 p-2.5" : "mt-6 p-4"}`}>
        <FeatureDiagram featureId={feature.id} />
        <figcaption className="mt-2 font-mono text-xs leading-relaxed text-text-muted">
          Schematic — any example values are fictional.
        </figcaption>
      </figure>
    </>
  );
}
