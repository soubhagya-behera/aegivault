import { FEATURES } from "./platformData";
import type { RefObject } from "react";

interface CapabilityRailProps {
  active: number;
  onSelect: (index: number) => void;
  railRef: RefObject<HTMLDivElement | null>;
}

/**
 * Capability rail — optional manual navigation above the track. Selection
 * scrolls the carousel container only (via the `onSelect` callback); the
 * rail never drives vertical page scrolling. The parent keeps the active
 * tab visible with a rail-container-local scroll.
 */
export default function CapabilityRail({ active, onSelect, railRef }: CapabilityRailProps) {
  return (
    <div ref={railRef} aria-label="Platform capabilities" role="group" className="mt-3 flex gap-2 overflow-x-auto pb-1">
      {FEATURES.map((feature, index) => {
        const isActive = index === active;
        return (
          <button
            key={feature.id}
            type="button"
            data-rail-tab={feature.id}
            onClick={() => onSelect(index)}
            aria-current={isActive ? "true" : undefined}
            aria-label={`Capability ${feature.index}: ${feature.title}`}
            className={`flex min-h-11 shrink-0 items-center gap-2.5 rounded-md border px-3.5 font-mono text-xs uppercase tracking-[0.1em] transition-[background-color,border-color,color,transform] duration-200 active:scale-[0.98] ${
              isActive
                ? "border-accent bg-surface font-bold text-text shadow-[0_8px_28px_rgba(65,105,255,0.25)]"
                : "border-border bg-transparent text-text-muted hover:border-border-strong hover:text-text"
            }`}
          >
            <span aria-hidden="true" className={isActive ? "text-accent" : ""}>
              {feature.index}
            </span>
            {feature.code}
          </button>
        );
      })}
    </div>
  );
}
