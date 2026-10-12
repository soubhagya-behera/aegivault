import type { ReactNode } from "react";

interface SectionIntroProps {
  eyebrow: string;
  title: string;
  /** id referenced by the section's aria-labelledby. */
  headingId: string;
  children?: ReactNode;
  className?: string;
}

/**
 * Shared editorial section header (Phase 3B): oxide hairline + mono eyebrow,
 * display heading, optional muted lead. Used by the platform, workflow, and
 * security sections so the three share one typographic rhythm.
 */
export default function SectionIntro({
  eyebrow,
  title,
  headingId,
  children,
  className = "",
}: SectionIntroProps) {
  return (
    <div className={className}>
      <p className="flex items-center gap-3 font-mono text-xs uppercase tracking-wide text-text-muted">
        <span aria-hidden="true" className="h-px w-8 shrink-0 bg-accent" />
        {eyebrow}
      </p>
      <h2
        id={headingId}
        className="mt-5 font-display text-2xl font-semibold leading-tight tracking-tight text-text sm:text-3xl"
      >
        {title}
      </h2>
      {children === undefined ? null : (
        <p className="mt-4 max-w-prose text-base leading-relaxed text-text-muted">
          {children}
        </p>
      )}
    </div>
  );
}
