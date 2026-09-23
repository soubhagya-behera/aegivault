# Aegivault Architecture

## Product purpose

Aegivault is a data sanitization and AI security platform. It inspects
structured data (starting with CSV files, later PostgreSQL tables and large
datasets), detects personally identifiable information (PII), applies
policy-driven sanitization (redaction, masking, tokenization), and records
every action in a tamper-evident audit ledger. An AI security gateway adds a
policy enforcement point for data sent to AI models.

## Core security/privacy problem

Teams share datasets with colleagues, vendors, and AI tools without knowing
which fields contain PII. Manual review does not scale, ad-hoc scripts leave
no audit trail, and raw data pasted into AI chat tools can leak sensitive
information. Aegivault addresses this by making PII detection,
sanitization, and auditing a single repeatable pipeline instead of scattered
one-off effort.

## Architecture style: modular monolith

The backend is a single Spring Boot deployable organized into modules with
explicit boundaries. Modules communicate through internal service interfaces;
there is no network hop between them. A module may only touch its own tables;
cross-module reads go through the owning module's service API.

This keeps local development and the first production deployment simple while
preserving the option to extract a module into a separate service later if a
genuine scaling or ownership requirement emerges.

## Currently implemented architecture

The repository currently contains the backend foundation plus the PII detection,
profiling, and CSV discovery modules:

* Spring Boot 4.1.1 application skeleton (Java 17, Maven).
* PostgreSQL datasource configuration (local `application.properties`,
  git-ignored; committed `application-example.properties` template).
* Spring Data JPA with `ddl-auto=validate` — Hibernate never modifies the
  schema.
* Flyway dependency, enabled, pointing at `db/migration/` (V1 datasets,
  V2 users/roles, V3 sanitization runs — all applied).
* Spring Security, Validation, and Actuator dependencies plus the
  `oauth2-resource-server` starter for JWT support, with a custom
  stateless security configuration.
* Test suites covering context load, Flyway/JPA persistence, identity and
  dataset REST APIs, PII detectors, PII profiling, CSV discovery/profiling,
  sanitization/transformation engine, the end-to-end CSV sanitization
  pipeline, and the sanitization run lifecycle (612 tests; see the verified test count below).
* Spring Security with a stateless JWT configuration (no custom login
  page, no sessions): Bearer tokens authenticate every request except
  `/api/auth/**` and actuator health/info; method security is enabled.
* V1 Flyway migration: `datasets` table (ingestion aggregate root, UUID key,
  UTC timestamps, owner/status indexes).
* V2 Flyway migration: `users` (BCrypt password hashes, unique
  lowercase-normalized email), `roles` (closed `USER`/`ADMIN` set, seeded),
  `user_roles` join (cascade on user delete, restrict on role delete).
* `Dataset` JPA entity mapped 1:1 to the Flyway schema plus a minimal
  repository; persistence proven by a `@DataJpaTest` against PostgreSQL.
* Identity module: `User` (implements Spring Security `UserDetails`
  directly; roles become `ROLE_<name>` authorities) and `Role` entities
  mapped 1:1 to the V2 schema, `UserDetailsService` backed by the
  `users` table.
* Password authentication: registration and login endpoints issue HMAC
  SHA-256 JWTs (60-minute expiry; `sub` = user UUID, plus `email` and
  `roles` claims) via Spring Security's `JwtEncoder`/`JwtDecoder`; login
  failures return an identical 401 that never reveals email existence.
  `Dataset.owner_subject` stays opaque TEXT with the convention
  `owner_subject = users.id` (JWT `sub`); ownership is enforced server-side.
* Owner-scoped dataset API: `POST /api/datasets` (201 + `Location`,
  server-set `CSV`/`UPLOADED`/trimmed name), `GET /api/datasets` (caller's
  rows only via `findByOwnerSubject`), `GET /api/datasets/{id}` (single
  `findByIdAndOwnerSubject` lookup; other-owner and missing ids return the
  same generic 404; malformed UUID returns 400). USER and ADMIN behave
  identically; no cross-user access exists. Responses never expose
  `ownerSubject`.
* 612 total tests verified (context load, dataset persistence, identity persistence,
  auth API, dataset API, PII detectors, PII profiling, CSV discovery, CSV profiling,
  sanitization/transformation engine, end-to-end CSV sanitization,
  sanitization run domain/persistence/lifecycle/execution/retrieval),
  0 failures, 0 errors, 0 skipped; the persistence/context suites run against the real
  PostgreSQL with no embedded database, and the CSV/profiling/sanitization/run-domain suites are pure unit tests.

* Eleven whole-value PII detectors (email, phone, credit card, IP address, UUID, API key,
  password-labelled values, JWT structure, person-name heuristic, address heuristic, and
  labelled custom identifiers) fronted by PiiDetectorRegistry (Spring List injection,
  deduplicated, deterministic PiiType ordering; failures propagate).
* Schema-aware PII profiling foundation (pii.profile, implemented; not persisted, no REST
  changes): PiiColumnProfiler analyses a deterministic bounded sample of the values
  supplied for one column (default 100, first-N order; supplied vs analyzed counts
  recorded), aggregates per-type detection counts and observed detection rates
  (denominator: analyzed non-blank values; observed rates only, not confidence or
  accuracy), and returns an immutable ColumnProfile with no raw values. DatasetProfiler
  composes one ColumnInput per column into an immutable DatasetProfile with deterministic
  column-name ordering.
* CSV schema discovery and bounded CSV profiling (dataset.csv, implemented; not persisted,
  no REST endpoint, no sanitization):

```text
CSV Input (caller-owned InputStream or text)
        |
   CSV Discovery / Reader
   (CsvDiscoveryService + CsvTokenizer)
        |
   Bounded Column Samples + CsvSchema (CsvSample)
        |
   ColumnInput (one per discovered column)
        |
   DatasetProfiler
        |
   PiiColumnProfiler
        |
   PiiDetectorRegistry
        |
   ColumnProfile / DatasetProfile
```

  The CSV layer only discovers and extracts values: the first record is the header, column
  names are preserved verbatim, blank/duplicate header names and any row whose width
  contradicts the header are rejected (a domain CsvParseException naming row numbers,
  column indexes, and limits only), quoted fields may hold delimiters, escaped quotes, and
  line breaks, and LF/CRLF/CR are all accepted. Sampling is bounded by explicit
  CsvLimits (maximum columns, sampled rows, field length, input bytes); the CSV sample
  size defaults to the profiler's own default sample size, so there is one sampling
  concept, and rows read are reported separately from rows retained for profiling. The
   facade CsvDatasetProfiler composes CSV discovery with the existing DatasetProfiler and
   returns a DatasetProfile without sanitizing, transforming, persisting, or calling any
   external service. CSV profiling is not yet persistent, the CSV sanitization/rewrite
   pipeline is not implemented, Spring Batch is not used, and there is no uploaded-file
   REST API yet. Database profiling tables, persisted policies, audit, gateway, Redis,
   and the dashboard also remain future work.

* Data sanitization / transformation engine foundation (sanitization, implemented;
  not persisted, no REST endpoint, no CSV/file/network/database access):

```text
raw value
    |
PiiDetectorRegistry (detection: what is this value?)
    |
PiiType
    |
TransformationPlan (explicit policy: what should happen to it?)
    |
TransformationRegistry / ValueTransformation (how it is rewritten)
    |
sanitized value
```

  Detection and transformation are separate concerns and live in separate
  classes: detectors never transform, transformations never detect, and the
  engine (`DataSanitizationService`) never infers a strategy from a detection
  result — it only applies the explicit `TransformationPlan` it is given.
  Six deterministic, pure strategies exist (`KEEP`, `REDACT`, `MASK`,
  `SYNTHETIC_EMAIL`, `SYNTHETIC_PHONE`, `HASH_SHA256`), resolved through the
  `TransformationRegistry` without a switch statement. Determinism comes from
  the input value and stable configuration alone (SHA-256 based; no randomness,
  no counters, no mapping store), so identical source values map to identical
  sanitized values within one operation. `ColumnSanitizer` applies one resolved
  strategy to a column's sampled values and returns a `SanitizedColumn` holding
  sanitized values plus policy metadata only — raw values are never retained in
  the result, never logged, never persisted, and never included in exception
  messages. `DefaultTransformationPolicy` is an explicit application policy
  covering all eleven PII types, not an intrinsic property of `PiiType` and not
  a safety or compliance claim; `HASH_SHA256` is deterministic
  pseudonymization-like transformation, not anonymization. No sanitization
  tables, repositories, migrations, REST endpoints, or new dependencies were
  added in this step.

* End-to-end CSV sanitization pipeline (dataset.csv, implemented; not
  persisted, no REST endpoint, no jobs):

```text
CSV Input (caller-owned InputStream)
        |
   CsvTokenizer, shared with discovery
        |
   header, verbatim / blank data records skipped / width checked
        |
   PiiDetectorRegistry per cell
        |
   first detection by PiiType name order, the multiple-PII rule
        |
   TransformationPlan, explicit policy
        |
   DataSanitizationService plus TransformationRegistry
        |
   CsvSanitizationWriter, LF-terminated escaped CSV
        |
sanitized CSV Output, caller-owned OutputStream, plus CsvSanitizationResult
```

  `CsvSanitizationService` orchestrates the existing components and nothing
  else: it reuses `CsvTokenizer` with the same `CsvLimits` column and field
  values as discovery, so profile and sanitize share quoting, width, and
  header policy; it reuses `PiiDetectorRegistry` with no new regexes and
  applies the single alphabetically-first `PiiType` when several detectors
  match, never bean order and never a score; it reuses
  `DataSanitizationService` and `TransformationRegistry` under the caller's
  explicit plan, fail-closed on unmapped types or unregistered strategies,
  and only the convenience overload uses `DefaultTransformationPolicy`.
  Blank cells are preserved without detection. Input is buffered once within
  the same 10 MiB byte limit, then records are parsed, transformed, and
  written one at a time: there is no row list, sampling limits never truncate
  sanitization, and output size may differ. The caller owns both streams:
  neither is closed, output is flushed, and only structural counts leave the
  call. Messages name rows, columns, limits, types, and strategies only. No
  upload API, persistence, jobs, Spring Batch, Redis, or anonymization claim
  was added.

* Sanitization run persistence and lifecycle (`sanitization.run`,
  implemented; persisted, no REST endpoint, no CSV/file/network access, no
  background workers):

```text
Dataset (owned)
    |
SanitizationRun (QUEUED, owner-scoped, policy snapshot frozen at creation)
    |
startRun -> RUNNING (started_at)
    |
completeRun -> COMPLETED (structural counts + completed_at)
failRun     -> FAILED (error code/stage/message + completed_at)
```

  A run records one sanitization *operation* against one dataset — never the
  sanitized file itself. The row holds the dataset FK, the owner copied from
  the dataset for join-free owner-scoped reads, the `RunStatus` state
  machine (`QUEUED -> RUNNING -> COMPLETED`, `QUEUED -> RUNNING -> FAILED`,
  terminal states accept nothing; illegal transitions throw
  `InvalidRunTransitionException`), an immutable `PolicySnapshot` (canonical
  JSON of the frozen type-to-strategy mapping plus policy labels, hand-built
  with alphabetically ordered keys so it never shifts with mapper settings),
  structural result counts (`RunResult`: rows in/out, blanks skipped, column
  count), safe failure metadata (`RunFailure`: code/stage/message with length
  caps, never a `Throwable`), and a `@Version` optimistic-locking column so
  concurrent lifecycle updates fail loudly instead of overwriting each other.
  `SanitizationRunService` (`createRun`/`get`/`listByDataset`/`startRun`/
  `completeRun`/`failRun`, one flushed transaction each) enforces dataset
  ownership, owner-scoped reads with identical missing-vs-foreign responses,
  and snapshot freezing; retrieval returns `SanitizationRunView`, which
  carries operation metadata only and deliberately excludes `ownerSubject`
  (an internal authorization field, mirroring `DatasetResponse`). `SanitizationRunExecutor` orchestrates the
  synchronous success path and nothing else — create (`QUEUED`), start
  (`RUNNING`), run the existing `CsvSanitizationService` on caller-owned
  streams with the same plan instance that was frozen, map the structural
  `CsvSanitizationResult` counts into `RunResult`, complete (`COMPLETED`) —
  with no new parsing, transformation, storage, or policy logic of its own.
  Documented-safe engine failures are mapped to `FAILED` instead of
  propagating: `CsvParseException` (structural facts only) becomes
  `CSV_PARSE_ERROR`, `MissingTransformationException` becomes `POLICY_GAP`,
  and other `SanitizationException`s become `TRANSFORM_ERROR`, each persisted
  through the existing `failRun` with its safe message verbatim plus
  `completedAt`. Anything else (programming errors, infrastructure failures)
  still propagates untouched with the run left `RUNNING`; there are no
  retries. The first HTTP exposure is `GET /api/runs/{runId}`
  (`SanitizationRunController`, thin by design): JWT subject plus UUID
  straight into the existing owner-scoped `get`, returning the hardened
  `SanitizationRunView` (200 owner / identical 404 foreign-or-missing /
  401 unauthenticated / 400 malformed UUID, mirroring the dataset endpoint
  conventions), plus an owner-scoped `GET /api/runs` listing returning the
   caller's runs newest-first (`createdAt` descending, id tiebreak; empty
   owner gets `200 []`). The application entry point is `POST /api/runs`
   (thin controller: validated `CreateRunRequest` carrying a dataset id plus
   a persisted-policy id, policy resolved owner-scoped through
   `SanitizationPolicyService.get` and mapped via `TransformationPlan.of`
   to the exact plan it declares, owner from JWT, straight into
   `executeStoredCsv`; 201 with `Location` plus the persisted view whether
   COMPLETED or FAILED, identical 404 for foreign-or-missing dataset/input
   and foreign-or-missing policy (one generic `{"message": "Dataset not
   found."}` that never reveals which reference failed), default 400
   validation, 401 unauthenticated; no transaction around streaming, no
   bytes back). The creation payload (`CreateRunRequest`: dataset id plus
   persisted-policy id, both `@NotNull` UUIDs, references only — never
   policy content) is validated before any domain work runs; the policy's
   name, version, and rules are read server-side and frozen into the run's
   immutable `PolicySnapshot`, so the run never follows later policy
   changes and the engine sees the resolved `TransformationPlan` only,
   exactly as before. Stored input is implemented as
  PostgreSQL BYTEA
  (ADR-013): one `dataset_inputs` row per dataset (primary-key dataset id,
  denormalized owner, `content BYTEA`, 10 MiB schema CHECK mirroring
  `CsvLimits`, `ON DELETE CASCADE` because bytes are dataset content, not
  history), served by `DatabaseDatasetInputSource` with the 10 MiB bound
  enforced while streaming before anything persists. `Dataset` holds no
  reference and the input entity holds no association back, so metadata
  queries never load the payload. BYTEA is the first backend for bounded
  MVP inputs, not a large-scale storage claim. Inputs arrive through
  `POST /api/datasets/{id}/input` (raw `text/csv` body streamed from the
  servlet request straight into storage — never pre-buffered by a message
  converter — owner from JWT, replace semantics, 200 with dataset id only;
  identical 404 foreign-or-missing, 401 unauthenticated, 400 malformed UUID,
  413 over the shared 10 MiB bound). The bounded read itself runs outside
  any database transaction: the owner check comes first (so a missing or
  foreign dataset is rejected without its body being read) and the upsert
  last, each in its own short transaction, so a slow client cannot pin a
  pooled connection and an open transaction for the length of its upload.
  Execution reads stored bytes
  through the seam (`executeStoredCsv`: open input first so missing/foreign
  input fails before any run row exists, then the unchanged
  create-start-sanitize-complete/fail flow on caller-provided output).
  Sanitized output persists separately per run (`sanitization_artifacts`,
  V5, ADR-014): BYTEA like the input store, but with its own explicit
  40 MiB bound — output is not assumed within the 10 MiB input bound
  because fixed substitutions expand short values several-fold —   run-keyed
  with `ON DELETE CASCADE`, no reference from the run entity, and no bytes
  in the view. The stored-input path captures output through a bounded tee
  (single 40 MiB bound reused, never redefined) into the artifact store
  after a successful sanitize but before completion — so a completed run
  always has exactly one artifact, a failed run never leaves a partial one,
  and bound overflow fails the run as `OUTPUT_TOO_LARGE` instead of
  completing. The artifact is served by `GET /api/runs/{runId}/artifact`
  (`SanitizationRunController`, thin like the other run endpoints): owner
  from JWT only, bytes from one `SanitizationArtifactStore.openArtifact`
  call — ownership check, completed-run requirement, and missing-artifact
  rule applied inside the store as one indistinguishable 404
  (`{"message": "Sanitization run not found."}`) for foreign runs,
  missing runs, unfinished runs, and runs without an artifact, alongside
  401 unauthenticated and 400 malformed UUID. Success returns 200 with
  `text/csv; charset=UTF-8` and
  `Content-Disposition: attachment; filename="sanitized-<runId>.csv"`,
  where the filename is derived only from the validated run id (never a
  dataset name, original filename, policy label, or owner value) and is
  built with `ContentDisposition` so header injection cannot occur. The
  stored bytes are streamed as the store's `InputStream` wrapped in an
  `InputStreamResource` — no `byte[]` body, no second full in-memory copy,
  chunked rather than read twice — and they are never parsed, rebuilt, or
  re-sanitized on download. The state machine, the 40 MiB bound, and the
  storage layout are unchanged. The V3
  migration constrains the table the same way (`RESTRICT` on
  dataset delete so history is never silently orphaned, error columns only on
  `FAILED`, `completed_at` required on terminal states, non-negative counts;
  indexes on `dataset_id` and `owner_subject` only — no status index until a
  real status query exists). Reusable, owner-scoped policies persist
  separately (`sanitization.policy`, V6): `SanitizationPolicy` (aggregate
  root: owner, name, version, optional description) plus one row per
  configured PII type in `sanitization_policy_rules`. The rules table
  reuses the existing `TransformationRule` / `TransformationPlan`
  vocabulary — no parallel rule model, no JSON blob — and its natural primary key
  `(policy_id, pii_type)` plus enum CHECKs make "one strategy per PII type
  per policy" a database guarantee as well as an aggregate one, while labels
  are bounded (255/255/1024) at the request, the aggregate, and the schema.
  The three endpoints (`POST /api/policies` returning 201 + `Location:
  /api/policies/{id}`, `GET /api/policies` listing only the caller's rows
  newest-first, `GET /api/policies/{policyId}` with identical 404 for
  foreign and missing ids) behave like the dataset and run APIs: owner from
  the JWT subject only, same error-body shape. The response carries `id`,
  labels, ordered rules (`{"piiType": "...", "strategy": "..."}`), and
  timestamps only — never `ownerSubject`, never persistence details, and
   never raw PII or CSV data, because no such column exists. Run creation
   consumes a persisted policy: `POST /api/runs` takes `{"datasetId":
   "...", "policyId": "..."}`, requires the JWT subject to own both
   resources, and freezes the policy's name/version/rules into the run's
   immutable snapshot; there is no inline-rules path and no policy update
   or delete endpoint. Beyond the
  endpoints described above (datasets, runs, artifact download, and
  reusable policies), no jobs, Spring Batch, Redis, background workers, or
  audit ledger was added.

Everything below under "planned" is design intent, not implementation.

## Planned architecture

### Major planned modules

| Module | Responsibility |
|---|---|
| Ingestion | Accept CSV uploads; later, connect to PostgreSQL source tables and chunk large datasets for streaming processing |
| PII detection | Identify PII in ingested data using pattern matching first, local AI assistance (Mock/Ollama) later |
| Policy engine | Decide which sanitization action applies to each detected finding, based on configurable rules |
| Sanitization | Execute redaction, masking, or tokenization and produce the sanitized output |
| Audit ledger | Persist a cryptographically linked, tamper-evident record of every detection and sanitization action |
| AI security gateway | Inspect outbound data bound for AI models, enforce policy before release, log the decision |
| Access control | Authentication, role-based authorization, per-user scoping of datasets and policies |
| Dashboard API | Serve sanitization results, findings, and audit views to the frontend |

### Backend / frontend boundaries

The Spring Boot backend owns all data processing, policy evaluation,
persistence, and audit writes, and exposes them over a versioned HTTP API.
The planned React (JavaScript) dashboard is a thin client: it uploads data,
displays findings and sanitized output, and renders audit history. It never
touches the database directly and performs no sanitization itself.

### PostgreSQL responsibility (planned)

PostgreSQL is the primary durable store: users, datasets and their metadata,
PII findings, policies, sanitized outputs or references to them, and the
audit ledger entries. All schema evolution goes through versioned Flyway
migrations; application code never issues DDL.

### Redis responsibility (planned)

Redis is reserved for operational controls, not durable data: rate limiting
on ingestion and gateway endpoints, short-lived processing locks for large
datasets, and caching of hot policy lookups. Anything that must survive a
restart lives in PostgreSQL.

### Data sanitization flow (planned)

1. Raw data enters through ingestion (CSV upload first).
2. The PII detection module scans fields and emits findings (field, detector,
   confidence).
3. The policy engine maps each finding to an action: redact, mask, tokenize,
   or leave untouched.
4. The sanitization module executes the actions and stores the sanitized
   output alongside a reference to the source.
5. Every step appends entries to the audit ledger.

### PII detection flow (planned)

Detection starts with deterministic detectors (regular expressions and
format checks for common identifiers such as emails, phone numbers, and
national ID formats). Each finding carries a confidence score. Local AI
assistance (Mock provider for tests, Ollama for development) is planned as a
second pass for ambiguous fields — detectors remain the authoritative first
pass so the pipeline works with no AI dependency.

### Policy enforcement concept (planned)

Policies are stored records (not code branches): e.g. "email addresses in
shared datasets must be masked" or "national IDs must be redacted".
The policy engine evaluates findings against the active policy set in a
defined precedence order and returns the winning action per finding.
Policy changes are versioned so past sanitization runs stay explainable.

### Cryptographically linked audit ledger concept (planned)

Each audit entry stores a hash of its own payload plus the hash of the
previous entry, forming a tamper-evident chain in PostgreSQL. Verification
replays the chain and reports the first broken link, if any. This is a
hash-chained database ledger — not a blockchain — and it provides
tamper-evidence (detection of modification), not absolute tamper-proofing.

### AI security gateway concept (planned)

Before data leaves Aegivault for an AI model, the gateway inspects the
outbound payload, runs PII detection and policy evaluation on it, and either
releases a sanitized payload, blocks the request, or flags it for review.
The decision and the payload hash are written to the audit ledger.

## High-level request/data flows (planned)

**Sanitize a CSV (MVP):**
`Dashboard → Ingestion API → PII detection → Policy engine → Sanitization →
PostgreSQL (results + findings) + Audit ledger → Dashboard (sanitized file)`

**Sanitize a PostgreSQL table (later phase):**
`Dashboard → Source connector (read-only) → chunked scan → detection →
policy → sanitized copy → audit ledger`

**AI gateway check (later phase):**
`Client → Gateway API → detection + policy on outbound payload →
release sanitized / block / flag → audit ledger`

## Important security boundaries

* The dashboard never accesses PostgreSQL or Redis directly; all access goes
  through the backend API with authentication and role checks.
* Raw PII and sanitized output are separated at the storage level; exports
  default to the sanitized form.
* The audit ledger is append-only from the application's perspective —
  application code has no update or delete path for ledger entries.
* Secrets and credentials live in environment-local configuration, never in
  the repository.
* Security and compliance properties of the system will be stated only as
  implemented and evidenced; no compliance certifications are claimed.
