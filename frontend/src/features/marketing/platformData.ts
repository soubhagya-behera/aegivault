/**
 * Platform showcase content (Phase 3B).
 *
 * Every description states a capability that exists in the backend today
 * (see ARCHITECTURE.md): CSV/PostgreSQL profiling with whole-value PII
 * detectors, policy-driven sanitization runs with CSV artifacts, PostgreSQL
 * discovery/binding, AI gateway request+response inspection (BLOCK is 200
 * data, never an error), rate limits/usage policies/enforced token budgets,
 * and the tamper-evident cryptographically linked audit ledger with a
 * verification replay. No invented endpoints, metrics, or certifications.
 */
export const FEATURE_IDS = [
  "profile",
  "sanitize",
  "postgres",
  "gateway",
  "limits",
  "audit",
] as const;

export type FeatureId = (typeof FEATURE_IDS)[number];

export interface PlatformFeature {
  id: FeatureId;
  /** Two-digit ordinal shown in the list and panel. */
  index: string;
  /** Short mono code for the panel kicker, e.g. "GATEWAY". */
  code: string;
  title: string;
  /** One-line summary shown in the selectable list. */
  summary: string;
  /** Full explanation shown in the detail panel. */
  description: string;
  /** Compact mobile tagline: the key idea in one short line. */
  tagline: string;
  /** Two short supporting points. */
  points: readonly [string, string];
}

export const FEATURES: readonly PlatformFeature[] = [
  {
    id: "profile",
    index: "01",
    code: "PROFILE",
    title: "Production data profiling & PII detection",
    summary: "Profile CSV uploads and bound PostgreSQL tables before anything moves.",
    description:
      "Whole-value detectors flag email, phone, credit card, IP address, UUID, API key, password-labelled values, JWT structure, and name/address heuristics. The profile reports per-column findings without ever storing the raw rows it read.",
    tagline: "Detects sensitive data by column without retaining raw rows.",
    points: [
      "Per-column detection counts and rates",
      "Detection and transformation are separate steps",
    ],
  },
  {
    id: "sanitize",
    index: "02",
    code: "SANITIZE",
    title: "Policy-driven sanitization, transformed CSV output",
    summary: "Redact, mask, or tokenize by explicit policy — with a CSV artifact per run.",
    description:
      "An explicit policy decides what happens to each detected type: redaction, masking, or tokenization. A run executes an immutable snapshot of that policy over the source and produces a transformed CSV artifact; the engine never infers a transformation from a detection.",
    tagline: "Transforms data only through an explicit, immutable policy.",
    points: [
      "Redact, mask, or tokenize by explicit plan",
      "Detectors never transform; transforms never detect",
    ],
  },
  {
    id: "postgres",
    index: "03",
    code: "POSTGRES",
    title: "PostgreSQL discovery, binding, and profiling",
    summary: "Discover schemas and tables read-only, bind safely, profile in place.",
    description:
      "Connect a PostgreSQL source read-only: discover its schemas and tables, bind chosen tables to a dataset with safe creation and deletion, and profile the bound data in place. Sanitization runs write sanitized copies instead of touching the source.",
    tagline: "Binds PostgreSQL tables read-only and profiles them in place.",
    points: [
      "Read-only schema and table discovery",
      "Safe binding lifecycle with sanitized copies",
    ],
  },
  {
    id: "gateway",
    index: "04",
    code: "GATEWAY",
    title: "AI Gateway request and response inspection",
    summary: "Inspect AI-bound requests and provider responses before release.",
    description:
      "Outbound requests are inspected for PII and secret patterns before any provider call, and provider responses are inspected before they are returned. A BLOCK is a decision, not an error: it arrives as normal data with reason codes and detected type names, and no provider payload is released.",
    tagline: "Inspects AI requests and responses; a block is data, not an error.",
    points: [
      "Request and response inspection under a fixed strict policy",
      "Request content is never persisted, logged, or ledgered",
    ],
  },
  {
    id: "limits",
    index: "05",
    code: "LIMITS",
    title: "Gateway limits, usage policies, and token budgets",
    summary: "Bound gateway traffic with per-actor limits and enforced budgets.",
    description:
      "Fixed-window request limits, per-actor usage policies, and enforced token budgets bound what the gateway will send. Quota decisions are recorded under their own event types — a policy refusal is not a security verdict — and usage is tracked separately from inspection.",
    tagline: "Bounds gateway traffic with limits, policies, and token budgets.",
    points: [
      "Per-actor request limits and usage records",
      "Token budgets enforced by reservation and settlement",
    ],
  },
  {
    id: "audit",
    index: "06",
    code: "AUDIT",
    title: "Cryptographically linked audit evidence",
    summary: "Append-only evidence with a verification replay for the whole chain.",
    description:
      "Every detection, sanitization, and gateway decision appends safe metadata to a tamper-evident, cryptographically linked ledger. Verification replays the chain and reports the first broken link — this detects modification; it does not make the ledger tamper-proof, and it is not a blockchain.",
    tagline: "Links tamper-evident audit entries; verification replays the chain.",
    points: [
      "Safe metadata only — never content, values, or secrets",
      "Verification replay reports the first broken link",
    ],
  },
];
