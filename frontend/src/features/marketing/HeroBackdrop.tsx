/**
 * HeroBackdrop: graphite opening composition — faint blueprint grid, one
 * restrained cobalt glow, and two slow-drifting trace strokes. Absolute,
 * transform-only, aria-hidden. Static under prefers-reduced-motion.
 */
export default function HeroBackdrop() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden">
      {/* Cobalt glow, single deliberate source upper-right */}
      <div
        className="absolute -right-40 -top-48 h-[560px] w-[720px] rounded-full"
        style={{ background: "radial-gradient(closest-side, rgba(65,105,255,0.28), transparent)" }}
      />
      <div
        className="absolute -bottom-56 -left-40 h-[480px] w-[560px] rounded-full"
        style={{ background: "radial-gradient(closest-side, rgba(65,105,255,0.12), transparent)" }}
      />
      {/* Blueprint grid */}
      <svg className="absolute inset-0 h-full w-full opacity-60" preserveAspectRatio="none" viewBox="0 0 1200 700">
        {Array.from({ length: 13 }, (_, i) => (
          <line key={`v-${i}`} x1={i * 100} y1="0" x2={i * 100} y2="700" stroke="var(--color-border)" strokeOpacity="0.5" strokeWidth="1" />
        ))}
        {Array.from({ length: 8 }, (_, i) => (
          <line key={`h-${i}`} x1="0" y1={i * 100} x2="1200" y2={i * 100} stroke="var(--color-border)" strokeOpacity="0.4" strokeWidth="1" />
        ))}
      </svg>
      {/* Drifting trace geometry with flowing dashes */}
      <svg className="mkt-ambient absolute -right-16 top-10 h-[380px] w-[520px] opacity-70 max-sm:opacity-25" viewBox="0 0 520 380" fill="none">
        <path d="M 20 300 C 140 240, 200 330, 320 250 S 460 200, 500 110" stroke="var(--mkt-cobalt)" strokeOpacity="0.55" strokeWidth="2" className="mkt-dash-flow" />
        <path d="M 40 340 C 160 280, 220 360, 340 285 S 465 235, 505 155" stroke="var(--color-border-strong)" strokeWidth="1.5" />
        <circle cx="320" cy="250" r="5" fill="var(--mkt-lime)" />
        <circle cx="320" cy="250" r="12" stroke="var(--mkt-lime)" strokeOpacity="0.5" />
        <rect x="468" y="96" width="52" height="22" rx="3" stroke="var(--mkt-cobalt)" strokeOpacity="0.7" />
      </svg>
      <div className="absolute inset-x-0 bottom-0 h-px bg-border opacity-70" />
    </div>
  );
}
