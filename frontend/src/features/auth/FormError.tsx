import { AlertCircle } from "lucide-react";

/**
 * Inline form error. Uses the danger state with an icon AND text (never color
 * alone) and is announced via role="alert".
 */
export default function FormError({ message }: { message: string }) {
  return (
    <div
      role="alert"
      className="flex items-start gap-2 rounded-md border border-danger bg-danger-bg px-3 py-2 text-sm text-danger"
    >
      <AlertCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>{message}</span>
    </div>
  );
}
