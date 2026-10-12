interface CarouselNavProps {
  active: number;
  count: number;
  canPrev: boolean;
  canNext: boolean;
  onPrev: () => void;
  onNext: () => void;
}

/**
 * Previous/next controls plus a horizontal-only progress indicator.
 * Progress derives from the active card index — never from vertical page
 * scroll — so ordinary page scrolling leaves it untouched.
 */
export default function CarouselNav({ active, count, canPrev, canNext, onPrev, onNext }: CarouselNavProps) {
  const position = `${String(active + 1).padStart(2, "0")} / ${String(count).padStart(2, "0")}`;
  const progress = count <= 1 ? 1 : active / (count - 1);
  return (
    <div className="mt-4 flex flex-wrap items-center gap-3">
      <div className="flex items-center gap-2" role="group" aria-label="Carousel navigation">
        <button
          type="button"
          onClick={onPrev}
          disabled={!canPrev}
          aria-label="Previous capability"
          className="flex min-h-11 min-w-11 items-center justify-center rounded-md border border-border bg-surface px-3 font-mono text-sm text-text transition-[background-color,border-color,transform] duration-200 hover:border-border-strong active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-border"
        >
          <span aria-hidden="true">←</span>
        </button>
        <button
          type="button"
          onClick={onNext}
          disabled={!canNext}
          aria-label="Next capability"
          className="flex min-h-11 min-w-11 items-center justify-center rounded-md border border-border bg-surface px-3 font-mono text-sm text-text transition-[background-color,border-color,transform] duration-200 hover:border-border-strong active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-border"
        >
          <span aria-hidden="true">→</span>
        </button>
      </div>
      <p className="font-mono text-xs uppercase tracking-[0.16em] text-text-muted" aria-live="polite">
        Capability <span className="font-bold text-text">{position}</span>
      </p>
      <div
        className="h-1 min-w-24 flex-1 overflow-hidden rounded-full bg-canvas"
        role="progressbar"
        aria-valuemin={1}
        aria-valuemax={count}
        aria-valuenow={active + 1}
        aria-label="Carousel progress"
      >
        <div className="h-full rounded-full bg-accent transition-[width] duration-200" style={{ width: `${progress * 100}%` }} />
      </div>
    </div>
  );
}
