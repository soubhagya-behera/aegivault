import type { InputHTMLAttributes } from "react";

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  id: string;
  label: string;
  /** Error message; when present the field is marked invalid and described by it. */
  error?: string;
  /** Optional helper text shown under the label. */
  hint?: string;
}

/**
 * Labeled text input with accessible error/help wiring. Presentation only.
 *
 * - The label is always associated via htmlFor/id.
 * - `aria-invalid` and `aria-describedby` are set only when relevant.
 * - Focus styling comes from the global :focus-visible rule in index.css.
 */
export default function Field({ id, label, error, hint, ...rest }: FieldProps) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-text">
        {label}
      </label>
      {hint && !error ? (
        <p id={`${id}-hint`} className="text-xs text-text-muted">
          {hint}
        </p>
      ) : null}
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`rounded-md border bg-surface-raised px-3 py-2 text-sm text-text placeholder:text-text-muted ${
          error ? "border-danger" : "border-border"
        }`}
        {...rest}
      />
      {error ? (
        <p id={`${id}-error`} className="text-xs text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
