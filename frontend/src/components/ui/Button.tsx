import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Loader2 } from "lucide-react";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary";
  loading?: boolean;
  children: ReactNode;
}

/**
 * Shared button. Presentation only.
 *
 * - Disabled while `loading` (prevents duplicate submissions) and shows a
 *   reduced-motion-safe spinner while KEEPING the visible label.
 * - `aria-busy` reflects the pending state for assistive tech.
 * - Focus styling comes from the global :focus-visible rule in index.css.
 */
export default function Button({
  variant = "primary",
  loading = false,
  type = "button",
  children,
  disabled,
  className = "",
  ...rest
}: ButtonProps) {
  const base =
    "inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60";
  const variants = {
    primary: "bg-accent text-accent-contrast hover:bg-accent-hover",
    secondary:
      "border border-border text-text hover:border-border-strong hover:bg-surface-raised",
  } as const;

  return (
    <button
      type={type}
      className={`${base} ${variants[variant]} ${className}`}
      disabled={disabled === true || loading}
      aria-busy={loading}
      {...rest}
    >
      {loading ? (
        <Loader2 aria-hidden="true" className="size-4 motion-safe:animate-spin" />
      ) : null}
      {children}
    </button>
  );
}
