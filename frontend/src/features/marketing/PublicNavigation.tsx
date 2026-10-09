import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Menu, X } from "lucide-react";
import { useAuth } from "../../auth/AuthProvider";
import BrandMark from "../../components/ui/BrandMark";

interface SectionLink {
  href: string;
  label: string;
}

/**
 * Anchor links to landing-page sections that later phases implement. The hrefs
 * are the contract with those phases; they are real fragment links now, not
 * dead buttons.
 */
const SECTION_LINKS: readonly SectionLink[] = [
  { href: "#platform", label: "Platform" },
  { href: "#workflow", label: "Workflow" },
  { href: "#security", label: "Security" },
];

/**
 * Public navigation (Phase 3A): compact sticky top bar with the animated brand
 * mark + wordmark, section anchors, and one primary authentication action.
 *
 * - The CTA uses the real routes: authenticated → the protected workspace
 *   (`/`, enforced by RequireAuth), signed out → `/login`.
 * - Mobile menu is a disclosure: `aria-expanded`/`aria-controls`, Escape closes
 *   and restores focus, an outside press closes, focus moves into the panel on
 *   open, and every target is ≥44px. It is plain DOM state — it works even if
 *   all animation is disabled.
 */
export default function PublicNavigation() {
  const { status } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const headerRef = useRef<HTMLElement | null>(null);
  const toggleRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);

  const authAction =
    status === "authenticated"
      ? { to: "/", label: "Open workspace" }
      : { to: "/login", label: "Sign in" };

  // Escape closes the menu and returns focus to the toggle; an outside press
  // closes it without stealing focus.
  useEffect(() => {
    if (!menuOpen) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setMenuOpen(false);
        toggleRef.current?.focus();
      }
    }
    function handlePointerDown(event: MouseEvent) {
      const header = headerRef.current;
      if (header && event.target instanceof Node && !header.contains(event.target)) {
        setMenuOpen(false);
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    document.addEventListener("mousedown", handlePointerDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.removeEventListener("mousedown", handlePointerDown);
    };
  }, [menuOpen]);

  // Move focus into the panel when it opens so keyboard users land in it.
  useEffect(() => {
    if (!menuOpen) return;
    panelRef.current?.querySelector<HTMLElement>("a")?.focus();
  }, [menuOpen]);

  function closeMenu() {
    setMenuOpen(false);
  }

  const ctaClassName =
    "inline-flex min-h-11 items-center justify-center rounded-md bg-accent px-4 text-sm font-medium text-accent-contrast transition-colors hover:bg-accent-hover";

  return (
    <header
      ref={headerRef}
      className="sticky top-0 z-40 border-b border-border bg-canvas"
    >
      <div className="mx-auto flex h-16 max-w-app items-center justify-between gap-4 px-6">
        <Link to="/home" className="group flex items-center gap-2.5">
          <BrandMark className="size-7 text-text transition-colors group-hover:text-accent" />
          <span className="font-display text-base font-semibold tracking-tight text-text transition-colors group-hover:text-accent">
            Aegivault
          </span>
        </Link>

        <nav aria-label="Sections" className="hidden items-center gap-6 md:flex">
          {SECTION_LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="text-sm text-text-muted underline-offset-4 transition-colors hover:text-text hover:underline"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="hidden md:block">
          <Link to={authAction.to} className={ctaClassName}>
            {authAction.label}
          </Link>
        </div>

        <button
          ref={toggleRef}
          type="button"
          className="inline-flex size-11 items-center justify-center rounded-md text-text-muted transition-colors hover:text-text md:hidden"
          aria-expanded={menuOpen}
          aria-controls="public-nav-menu"
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span className="sr-only">
            {menuOpen ? "Close navigation menu" : "Open navigation menu"}
          </span>
          {menuOpen ? (
            <X aria-hidden="true" className="size-5" />
          ) : (
            <Menu aria-hidden="true" className="size-5" />
          )}
        </button>
      </div>

      <div
        id="public-nav-menu"
        ref={panelRef}
        hidden={!menuOpen}
        className="absolute inset-x-0 top-full border-b border-border bg-canvas md:hidden"
      >
        <nav aria-label="Sections" className="flex flex-col px-6">
          {SECTION_LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              onClick={closeMenu}
              className="border-b border-border py-3.5 text-sm text-text-muted transition-colors hover:text-text"
            >
              {link.label}
            </a>
          ))}
        </nav>
        <div className="px-6 py-4">
          <Link to={authAction.to} onClick={closeMenu} className={`${ctaClassName} w-full`}>
            {authAction.label}
          </Link>
        </div>
      </div>
    </header>
  );
}
