import type { ReactNode } from "react";
import { ShieldCheck } from "lucide-react";

interface AuthShellProps {
  title: string;
  subtitle: string;
  children: ReactNode;
  footer: ReactNode;
}

/**
 * Shared frame for the login and registration pages: centered, responsive,
 * restrained Aegivault treatment (obsidian canvas, bone text, oxide mark).
 * Presentation only — no auth logic.
 */
export default function AuthShell({
  title,
  subtitle,
  children,
  footer,
}: AuthShellProps) {
  return (
    <main className="mx-auto flex min-h-dvh max-w-app flex-col justify-center px-6 py-16">
      <div className="mx-auto w-full max-w-md">
        <div className="flex items-center gap-2 text-accent">
          <ShieldCheck aria-hidden="true" className="size-5" />
          <span className="font-mono text-xs uppercase tracking-wide">Aegivault</span>
        </div>
        <h1 className="mt-4 font-display text-2xl font-semibold tracking-tight text-text">
          {title}
        </h1>
        <p className="mt-2 text-sm text-text-muted">{subtitle}</p>
        <div className="mt-8 border-t border-border pt-8">{children}</div>
        <div className="mt-6 text-sm text-text-muted">{footer}</div>
      </div>
    </main>
  );
}
