import { CheckCircle2, Info } from "lucide-react";
import { API_BASE_URL } from "../../lib/env";

/**
 * TEMPORARY protected-workspace placeholder.
 *
 * This is NOT the dashboard. It confirms the authenticated shell renders and
 * stays an honest placeholder — no fabricated metrics, datasets, or audit data.
 * It will be replaced by the real workspace routes in later phases.
 */
const checks = [
  "Authenticated application shell",
  "In-memory session (no persisted token)",
  "Tailwind v4 semantic tokens (tokens.css)",
  "react-router-dom protected routing",
  "lucide-react icon set",
];

export default function ScaffoldStatus() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-app flex-col justify-center px-6 py-16">
      <p className="font-mono text-xs uppercase tracking-wide text-accent">
        Workspace
      </p>

      <h1 className="mt-3 font-display text-3xl leading-tight tracking-tight text-text">
        You're signed in.
      </h1>

      <p className="mt-4 max-w-measure text-md leading-relaxed text-text-muted">
        This is a temporary placeholder for the protected workspace. The
        dashboard, dataset, sanitization, PostgreSQL, gateway, and audit modules
        are implemented in later phases. No production data is shown here.
      </p>

      <section
        aria-labelledby="scaffold-checks-heading"
        className="mt-10 border-t border-border pt-6"
      >
        <h2 id="scaffold-checks-heading" className="text-sm font-semibold text-text">
          Active foundation
        </h2>
        <ul className="mt-4 space-y-2">
          {checks.map((item) => (
            <li key={item} className="flex items-center gap-3 text-sm text-text-muted">
              <CheckCircle2 aria-hidden="true" className="size-4 shrink-0 text-success" />
              <span>{item}</span>
            </li>
          ))}
        </ul>
      </section>

      <p className="mt-10 flex items-start gap-3 border-t border-border pt-6 font-mono text-xs text-info">
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span>
          API base URL: <span className="text-text">{API_BASE_URL}</span> — proxied to
          the backend in development.
        </span>
      </p>
    </main>
  );
}
