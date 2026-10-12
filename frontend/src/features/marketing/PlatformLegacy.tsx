import { useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { FEATURES, type FeatureId } from "./platformData";
import PlatformPanelContent from "./PlatformPanelContent";
import { DUR, EASE } from "./motion";

/**
 * Legacy capability explorer: the Phase 3E vertical tablist + single detail
 * panel, unchanged in contract (tab roles, roving tabindex, Arrow/Home/End,
 * automatic activation). Serves reduced-motion users on every viewport, who
 * must never be pinned or folded. Phase 3F transition fix: `AnimatePresence`
 * `mode="popLayout"` crossfades exit/enter so rapid selection never passes
 * through a blank frame (`mode="wait"` unmounted first — the ~14s wash-out).
 */
export default function PlatformLegacy() {
  const reduce = useReducedMotion();
  const [selectedId, setSelectedId] = useState<FeatureId>("profile");
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const selectedIndex = Math.max(0, FEATURES.findIndex((f) => f.id === selectedId));
  const selected = FEATURES.find((f) => f.id === selectedId);

  function handleTabKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    let nextIndex: number;
    switch (event.key) {
      case "ArrowDown":
      case "ArrowRight":
        nextIndex = Math.min(selectedIndex + 1, FEATURES.length - 1);
        break;
      case "ArrowUp":
      case "ArrowLeft":
        nextIndex = Math.max(selectedIndex - 1, 0);
        break;
      case "Home":
        nextIndex = 0;
        break;
      case "End":
        nextIndex = FEATURES.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    const target = FEATURES[nextIndex];
    if (target !== undefined) {
      setSelectedId(target.id);
      tabRefs.current[nextIndex]?.focus();
    }
  }

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 lg:gap-8">
      <div
        role="tablist"
        aria-label="Platform capabilities"
        aria-orientation="vertical"
        onKeyDown={handleTabKeyDown}
        className="flex flex-col gap-2 lg:col-span-5"
      >
        {FEATURES.map((feature, index) => {
          const isSelected = feature.id === selectedId;
          return (
            <button
              key={feature.id}
              ref={(element) => {
                tabRefs.current[index] = element;
              }}
              type="button"
              role="tab"
              id={`platform-tab-${feature.id}`}
              aria-selected={isSelected}
              aria-controls={`platform-panel-${feature.id}`}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => setSelectedId(feature.id)}
              className={`relative flex w-full items-center gap-4 overflow-hidden rounded-lg border px-4 py-3.5 text-left transition-[transform,background-color,border-color,box-shadow] duration-200 focus-visible:outline-none active:scale-[0.99] ${
                isSelected
                  ? "border-accent bg-surface shadow-[0_12px_36px_rgba(65,105,255,0.25)]"
                  : "border-border bg-transparent hover:-translate-y-0.5 hover:border-border-strong hover:bg-surface/70"
              }`}
            >
              {isSelected ? (
                <motion.span
                  aria-hidden="true"
                  layoutId="platform-active-rule"
                  className="absolute inset-y-0 left-0 w-1.5 bg-accent"
                  transition={{ duration: reduce ? 0 : DUR.panel, ease: EASE }}
                />
              ) : null}
              <span
                aria-hidden="true"
                className={`font-display text-2xl font-bold tabular-nums ${
                  isSelected ? "text-accent" : "text-text-muted/60"
                }`}
              >
                {feature.index}
              </span>
              <span className="min-w-0">
                <span className="block font-display text-[15px] font-semibold leading-snug tracking-tight text-text">
                  {feature.title}
                </span>
                <span className="mt-0.5 block truncate text-[13px] text-text-muted">
                  {feature.summary}
                </span>
              </span>
            </button>
          );
        })}
      </div>

      <div className="lg:col-span-7">
        {selected === undefined ? null : (
          <div
            role="tabpanel"
            id={`platform-panel-${selected.id}`}
            aria-labelledby={`platform-tab-${selected.id}`}
            tabIndex={0}
            className="relative rounded-xl border border-border bg-surface p-6 shadow-[0_24px_70px_rgba(20,26,38,0.14)] focus:outline-none md:p-8"
          >
            {reduce ? (
              <div key={selected.id}>
                <PlatformPanelContent feature={selected} />
              </div>
            ) : (
              <AnimatePresence mode="popLayout" initial={false}>
                <motion.div
                  key={selected.id}
                  initial={{ opacity: 0, y: 14, scale: 0.995 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: -10, scale: 0.998 }}
                  transition={{ duration: DUR.panel, ease: EASE }}
                >
                  <PlatformPanelContent feature={selected} />
                </motion.div>
              </AnimatePresence>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
