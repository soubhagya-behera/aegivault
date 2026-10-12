import { Outlet } from "react-router-dom";
import PublicNavigation from "../features/marketing/PublicNavigation";

/**
 * Public site frame (Phase 3A; marketing scope 3E): skip link, public
 * navigation, and a single `<main>` landmark for the routed public page.
 * `mkt-nav-scope` keeps nav actions/focus cobalt (marketing-only CSS vars —
 * the dashboard never sees them). Presentation only — no data or auth logic.
 */
export default function PublicLayout() {
  return (
    <div className="mkt-nav-scope min-h-dvh bg-canvas text-text">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-accent focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-accent-contrast"
      >
        Skip to main content
      </a>
      <PublicNavigation />
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      {/* Site footer arrives with a later landing-page phase. */}
    </div>
  );
}
