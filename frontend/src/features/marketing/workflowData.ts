/**
 * Workflow chapter content: the five-stage SOURCE → VERIFY sequence. Shared
 * by the desktop narrative and the mobile panels in `WorkflowSection` (plus
 * the standalone `WorkflowCard`) so all representations stay in sync. All
 * example values are fictional.
 */
export interface WorkflowStep {
  index: string;
  name: string;
  line: string;
  detail: string;
}

export const WORKFLOW_STEPS: readonly WorkflowStep[] = [
  { index: "01", name: "Source", line: "A CSV upload or a bound PostgreSQL table.", detail: "Records stage untouched — the source is never written to." },
  { index: "02", name: "Inspect", line: "Detectors report findings; nothing is changed.", detail: "Sensitive columns get badged in place; every value stays raw." },
  { index: "03", name: "Sanitize", line: "An approved policy transforms the data.", detail: "Redact, mask, or tokenize — a transformed copy is written." },
  { index: "04", name: "Govern", line: "Limits and usage policy decide AI-bound requests.", detail: "ALLOW ships under budget; BLOCK arrives as 200 data with reasons." },
  { index: "05", name: "Verify", line: "The audit chain replays and checks itself.", detail: "Every link verified — the first broken link is reported." },
];
