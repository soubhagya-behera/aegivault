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
* Stored dataset profiles (`dataset.profile`, V8, persisted, read-only
  REST): `dataset_profiles` (one row per dataset — the dataset id is the
  primary key, owner copied for join-free owner-scoped reads, profiler
  metadata stored verbatim), `dataset_profile_columns` (one row per column:
  ordinal in the profiler's deterministic column-name order, name, and
  supplied/analyzed/analyzable counts), and `dataset_profile_detections`
  (one row per detected PII type per column: enum name plus the
  profiler-reported count and observed rate; columns with no detections
  have no rows). There is deliberately no value, sample, or content column
  anywhere, so raw CSV values, samples, PII values, and sanitized values
  have nowhere to be stored. Deletes cascade from the dataset (a profile is
  derived, recomputable dataset metadata, like inputs and artifacts — not
  operation history like runs), and a re-save deletes the previous rows
  before storing the new aggregate, so no stale columns or detections
  survive. `DatasetProfileService.saveProfile` stores an already-computed
  `DatasetProfile` for an owned dataset (foreign or missing datasets get
  the same generic 404) and `getProfile` reads it back verbatim — profiling
  is never re-run on read. HTTP exposure is read plus one explicit trigger:
  `GET /api/datasets/{datasetId}/profile` (thin controller over the
  owner-scoped `get`: 200 with dataset/column metadata and detection
  counts/rates/types, one identical 404 for foreign, missing, and
  not-yet-profiled datasets, 401 unauthenticated, 400 malformed UUID; no
  `ownerSubject` in the response) and `POST
  /api/datasets/{datasetId}/profile` (thin controller over
  `DatasetProfilingService`: owner from the JWT subject, stored input opened
  through `DatasetInputSource`, profiled with the existing
  `CsvDatasetProfiler` inside the existing CSV limits and bounded sample,
  saved through `DatasetProfileService.saveProfile`, then re-read, so the
  200 response is literally the persisted state in the `GET` shape: same
  metadata and detection counts/rates/types, one identical 404 for foreign,
  missing, and not-yet-uploaded datasets, 401 unauthenticated, 400 malformed
  UUID, 422 with the safe structural message when the stored CSV cannot be
  discovered; no `ownerSubject`, no raw values. Re-triggering replaces the
  previous profile cleanly with no stale rows). CSV upload and run creation
  remain untouched: upload stores bytes only and never profiles
  automatically. A read-only `GET
  /api/datasets/{datasetId}/profile/transformation-preview` maps the
  persisted profile through the existing default transformation policy
  (per-type counts/rates plus the suggested strategy; no profiling, no
  sanitization, no policy/run/audit writes, no raw values). A companion
  `POST /api/datasets/{datasetId}/profile/transformation-preview/policy`
  persists those suggested rules as a normal owner-scoped policy through
  the existing policy service (detected types only, default strategies,
  same `PolicyResponse` and `Location` as `POST /api/policies`; empty
  detection sets fail 400 before anything persists; no run, no execution,
  no dataset/profile/CSV change, no audit writes).
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
  The five endpoints (`POST /api/policies` returning 201 + `Location:
  /api/policies/{id}`, `GET /api/policies` listing only the caller's rows
  newest-first, `GET /api/policies/{policyId}` with identical 404 for
  foreign and missing ids, `PUT /api/policies/{policyId}` replacing one
  owned policy's labels and entire rule set in place — same id, same owner,
  no new row, `updatedAt` advanced, same response shape as the GET — and
  `DELETE /api/policies/{policyId}` removing one owned policy and its rules
  with 204 and no body) behave
  like the dataset and run APIs: owner from
  the JWT subject only, same error-body shape. The response carries `id`,
  labels, ordered rules (`{"piiType": "...", "strategy": "..."}`), and
  timestamps only — never `ownerSubject`, never persistence details, and
   never raw PII or CSV data, because no such column exists. Run creation
   consumes a persisted policy: `POST /api/runs` takes `{"datasetId":
   "...", "policyId": "..."}`, requires the JWT subject to own both
   resources, and freezes the policy's name/version/rules into the run's
  immutable snapshot; there is no inline-rules path. A policy update or
  delete never touches existing runs: runs hold
  copied snapshot columns, not a foreign key to the mutable policy, so a
   deleted policy leaves every run readable and every artifact downloadable.
  Execution flow: `Dataset` -> persisted `SanitizationPolicy` ->
  `TransformationPlan` -> `SanitizationRun` (immutable policy snapshot) ->
  sanitized artifact. Tamper-evident audit ledger
   foundation (`audit`, V7): `audit_ledger_entries` rows (1-based gapless
   `sequence_number` with a UNIQUE constraint, event type/actor/resource
   labels, an optional resource id, a safe-metadata `event_data` document,
   `previous_hash`, and a server-derived SHA-256 `entry_hash` that is UNIQUE
   itself) appended through `AuditLedgerService` (tail supplies sequence and
   previous hash; the first entry uses the deterministic `GENESIS` previous
   hash) and replayed by `AuditLedgerVerificationService`, which recomputes
   every hash from the stored fields — never trusting the stored hash — and
   reports the first broken sequence, mismatch, or link as a value, with an
   empty ledger verifying valid. The canonical hashed form is an explicit
   length-prefixed format, so no delimiter can alias another field tuple;
   `event_data` holds safe metadata only (no CSV, PII, secrets, or request
   bodies — a caller contract the schema cannot see). Run lifecycle events
   are recorded: the run executor appends `SANITIZATION_RUN_CREATED` after
   a run is started and `SANITIZATION_RUN_COMPLETED` or
   `SANITIZATION_RUN_FAILED` after the terminal transition commits, each
   with the owner as actor, the run id as resource, and structural metadata
   only — so every finished run contributes exactly two entries. An audit
   infrastructure failure is never swallowed and never reported as success:
   it surfaces as a generic 500 with no storage details. There are no REST
   endpoints and no other integration yet: nothing else appends, and there
   is no retry queue or background worker. Ledger integrity is verifiable
   through `GET /api/audit/verify` (authenticated, USER and ADMIN alike;
   the shared ledger needs no owner filtering): one full replay per call
   returning the verdict, the replayed-entry count, and — only when
   invalid — the failure code, never ledger content. A verification that
   ran and found a break stays a normal 200 with `valid=false`; only an
   infrastructure failure that prevented the replay becomes a generic 500
   with no storage details. Single-instance
   sequencing only — concurrent appends fail loudly on the UNIQUE
   constraint instead of forking, with no distributed locking. Beyond the
   endpoints described above (datasets, runs, artifact download, and
   reusable policies), no jobs, Spring Batch, Redis, background workers, or
   audit event integration was added.

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

### Cryptographically linked audit ledger concept (foundation implemented)

The foundation above is implemented: hash-chained PostgreSQL rows plus a
verification replay. Each audit entry stores a hash of its own payload plus the hash of the
previous entry, forming a tamper-evident chain in PostgreSQL. Verification
replays the chain and reports the first broken link, if any. This is a
hash-chained database ledger — not a blockchain — and it provides
tamper-evidence (detection of modification), not absolute tamper-proofing.
Event integration (which operations append) and any read API are still
planned, not implemented.

### AI security gateway concept (inspection foundation implemented; enforcement planned)

Before data leaves Aegivault for an AI model, the gateway inspects the
outbound payload, runs PII detection and policy evaluation on it, and either
releases a sanitized payload, blocks the request, or flags it for review.
The decision and the payload hash are written to the audit ledger.

Implemented so far is the internal inspection/decision foundation only
(`gateway` package): an immutable inspection request, the existing
`PiiDetectorRegistry` applied per token, obvious-secret recognition through
the existing API-key/JWT detectors, a small block-on-PII/block-on-secrets
policy, and one deterministic ALLOW/BLOCK decision carrying safe reason
codes and detected type names — never matched values or request content.
There is deliberately no proxy, no LLM or network call, and no
persistence or logging of request content. Inspection is exposed
through authenticated `POST /api/gateway/inspect` (JWT subject as actor,
server-generated request id, fixed strict policy; a BLOCK verdict is
returned as 200 data, never an error status; still no provider forwarding
and no persistence of request bodies). Every successfully inspected
request appends exactly one entry to the existing tamper-evident audit
ledger (`AI_GATEWAY_INSPECTION_ALLOWED` or
`AI_GATEWAY_INSPECTION_BLOCKED`, actor from the JWT, resource id from
the server-generated request id) carrying safe metadata only — model,
verdict, reason codes, detected type names — so request content,
matched PII values, and secrets never enter the ledger; requests that
never reach inspection (unauthenticated, invalid, oversized) append
nothing. Enforcement beyond inspection recording and provider
integration remain planned, not implemented. A minimal LLM provider
abstraction exists alongside inspection (`gateway.provider`: `LlmProvider`
 with immutable `LlmRequest`/`LlmResponse` carrying model and content plus
 optional provider-reported usage metadata (`LlmUsage`: prompt, completion,
 and total token counts, each unknown unless the provider supplied it),
plus a deterministic zero-configuration `MockLlmProvider` whose labelled
 mock completions never touch the network) for local and test use only.
 A local Ollama provider implementation also exists (`OllamaLlmProvider`
 behind the same `LlmProvider` interface, using the Ollama generate API
 over plain HTTP via the existing Spring `RestClient` — no SDK, no new
 dependencies — with typed localhost-only configuration for base URL plus
connection/read timeouts and generic safe failures). Provider selection
  is configuration-driven only (`aegivault.gateway.provider`, `MOCK`
  default, `OLLAMA` supported; never inferred from the model name, a URL,
  content, or headers, and never chosen by callers of
  `POST /api/gateway/complete`): exactly one `LlmProvider` bean is active
  at a time, wired at startup, and the default deployment still uses the
  mock, so Ollama support exists in the application without the deployment
  being configured to use it. No external cloud provider exists.
`POST /api/gateway/complete` inspects under the fixed strict policy and
forwards only ALLOW requests to the `LlmProvider` resolved through the
small `LlmProviderSelector` abstraction (`select(model)` validates the
model and returns the single configuration-wired provider) through a small application service; BLOCK returns the safe
decision as 200 data and never reaches the provider, and each inspected
request keeps the single metadata-only audit entry. Every successful
provider response is inspected separately before it reaches the client
through `ProviderResponseInspectionService`, which reuses the same
`PiiDetectorRegistry` plus secret recognition under the same strict
policy (PII blocks, secrets block) into a distinct
`ProviderResponseInspectionResult` — a second decision that never reuses
the request result. A clean provider response returns as ALLOW with the
 provider completion; a sensitive provider response returns as BLOCK
 (200 data with verdict/reasons/detected type names and no provider
 payload) instead of being returned. Provider output shares the 64 KiB
 (65,536-character) boundary concept with the request input bound: the
 completion service rejects oversized provider content with the generic
 provider-failure 500 before response inspection runs — never truncated,
 never partially inspected, never returned, never a BLOCK verdict.
  Provider response content is never persisted, never logged, and never
   enters the audit ledger. Provider responses may include usage metadata
 (`LlmUsage`) that is provider-reported when available and preserved as
 unknown otherwise: the mock always reports unknown usage (its character
 count is never presented as tokens) and the Ollama provider maps only
 the documented `prompt_eval_count`/`eval_count` fields, leaving absent
 or invalid counts unknown rather than estimating them. Usage is exposed
 to clients only on ALLOW responses (as part of the provider completion;
 BLOCK carries no provider payload and therefore no usage), is never part
 of security inspection or audit event data, and no budget, quota, cost,
 or accounting enforcement exists yet. Every provider invocation that
 returns a response is additionally persisted once as a gateway usage
 record (`gateway.usage`: `GatewayUsageRecord` in PostgreSQL via Flyway
 V9 — request id, actor, model, exact provider-reported counts with
 unknown preserved as null, and outcome `DELIVERED` or
 `SECURITY_BLOCKED`; metadata only, never prompt or response content).
  Persisted usage has an internal read-only query layer
  (`GatewayUsageQueryService`, repository only): actor-scoped history
  newest-first, one database-side aggregate row per actor (exact row
  count with token totals that stay null when no values are known), and
  actor-scoped aggregation over an explicit UTC time window
  (`[from, to)`: `from` inclusive, `to` exclusive, computed database-side
  with identical null-means-unknown semantics as groundwork for future
  governance and budget enforcement; budgets, quotas, cost, and pricing
  are NOT implemented yet).
  The authenticated self-service API `GET /api/gateway/usage` exposes
  that layer to the currently authenticated actor only: the actor comes
  exclusively from the verified JWT subject (never a parameter, path,
  body, or header), history is bounded to the newest 100 records
  (createdAt DESC, id DESC, bounded in the repository/database query),
  and the response carries usage metadata only (request id, model,
  token counts with null-means-unknown preserved, outcome, timestamp,
  plus the database-side aggregate) — no actor subjects, no prompt or
  response content, no secrets, no PII, no Redis information. There is
  The companion `GET /api/gateway/usage/aggregate?from=&to=` exposes the
  windowed aggregate: the actor again comes exclusively from the verified
  JWT subject, both bounds are required ISO-8601 instants, the window is the
  same UTC half-open interval (`from <= createdAt < to`) the query service
  owns, and a missing, malformed, or non-positive window is a safe 400 that
  never reaches the database. It returns exactly one metadata-only aggregate
  object — record count plus token totals with null-means-unknown preserved
  — with no actor subject, no window echo, no request ids, models, prompt or
  response content, provider details, database ids, or pagination.
  Owner-scoped gateway usage *policies* now exist as persisted definitions
  only (`gateway.policy`: `GatewayUsagePolicy` in PostgreSQL via Flyway V10 —
  owner subject, label, optional description, nullable
  requests_per_minute / requests_per_day / tokens_per_day limits, an enabled
  switch, and timestamps; a limit is strictly positive or absent, and at
  least one must be present), managed through
  `POST/GET/PUT/DELETE /api/gateway/policies` with the owner again taken
  only from the verified JWT subject. **These policies are NOT enforced:**
  no gateway code path reads the table, no counter is derived from it, and
  current rate limiting remains entirely controlled by `GatewayRateLimiter`
  configuration. They express request and token *quantities* only — pricing,
  budgets, billing, and cost accounting remain unimplemented.
  An explicit `GatewayUsagePolicyResolver` now defines how one actor's
  effective policy is chosen, and it deliberately refuses to guess: only
  that actor's *enabled* policies are candidates, exactly one resolves,
  zero means no policy (a normal, non-error outcome), and two or more is
  ambiguous configuration that raises a safe exception naming no owner,
  policy, count, or database detail. No tie-break exists — newest, oldest,
  tightest, loosest, and alphabetical are all rejected, because enforcing
  the wrong limit is worse than not resolving. The resolver reads one
  owner-scoped, deterministically ordered read and depends only on the
  policy repository. **The gateway is not policy-controlled yet:** nothing
  calls the resolver, so resolution still has zero runtime effect, and
  there is no policy activation, default-policy, or uniqueness mechanism.
  A pure `GatewayUsagePolicyEvaluator` now answers whether one
  already-collected usage snapshot satisfies one policy. It is a static,
  dependency-free function of two in-memory objects — not a Spring bean,
  with no repository, Redis, rate-limiter, controller, provider, or audit
  dependency. It checks every configured limit (a null limit is
  unconstrained; a limit is the highest permitted value, so exactly at the
  limit is fine) and reports `ALLOW`, `LIMIT_EXCEEDED`, or `USAGE_UNKNOWN`,
  plus a distinct `INACTIVE` state so a disabled policy is never silently
  treated as satisfied. Unknown token usage is explicitly distinguishable:
  when a token limit is configured but the total is untrustworthy the
  result is `USAGE_UNKNOWN`, never `LIMIT_EXCEEDED` and never `ALLOW`,
  because an unknown total is neither evidence of excess nor proof of
  compliance, and it is never read as zero. A definite request-count
  violation outranks token uncertainty while `tokenUsageUnknown` keeps that
  uncertainty visible. All tripped limits are reported together in a fixed
  declaration order, never in set-iteration order. Nothing in the gateway
  traffic path calls the evaluator and nothing collects usage counters for
  it, so **policies are still not enforced**; deciding what an inactive
  policy or an unknown total should mean is a later, separate decision.
  That evaluator now has a read-only data source:
  `GatewayUsagePolicyUsageSnapshotProvider` builds the snapshot for one
  actor and one supplied instant from two windowed database-side aggregate
  reads over persisted usage rows. The windows are UTC and half-open — the
  minute is `[minuteStart, minuteStart + 1 minute)` and the day is
  `[dayStart, dayStart + 1 day)` — never derived from the JVM default zone.
  The request counts are counts of *persisted gateway usage records*, i.e.
  recorded provider invocations that returned a response: request-side
  BLOCKs, rate-limit rejections, and provider or selector failures write no
  usage row, so these limits bound recorded provider usage rather than
  inbound traffic. A daily token total is reported only when every matching
  row supplied one; a single unknown provider total makes the whole day
  unknown and the partial sum is discarded, so unknown propagates as
  unknown instead of silently undercounting. An empty day is the one
  known-zero case. **The provider is not connected to enforcement** — it
  reads only, with no counters, reservation, or check-and-consume — and
  policies remain unenforced.
  These three pieces are now composed by a small internal
  `GatewayUsagePolicyDecisionService`, which answers one question — "what is
  this actor's current gateway usage policy decision, right now?" — by
  resolving the policy, reading the usage snapshot for the same actor at the
  caller's supplied instant, and evaluating the one resolved policy against
  that one snapshot. It adds no new evaluation logic: every limit comparison
  and the violation order still come solely from the pure
  `GatewayUsagePolicyEvaluator`, which the service merely calls and whose
  answer it projects. Its result is a flat
  `GatewayUsagePolicyDecisionOutcome` with five distinguishable states:
  `NO_POLICY`, `ALLOW`, `LIMIT_EXCEEDED`, `USAGE_UNKNOWN`, and `INACTIVE`.
  **`NO_POLICY` is deliberately not `ALLOW`**: an actor with no enabled
  policy is not an actor whose limits were checked and found satisfied, so
  the two remain distinguishable and no usage is even read when no policy
  exists. Ambiguous configuration is **not** converted into a decision:
  `GatewayUsagePolicyAmbiguousException` propagates unchanged and no policy
  is picked, because enforcing an arbitrarily chosen limit is worse than
  declaring the configuration undecidable. The instant is supplied by the
  caller and forwarded unchanged — the service never calls
  `Instant.now()` — so the answer is deterministic and testable, and the same
  trimmed actor is used for both lookups so a policy can never be evaluated
  against another actor's usage. The result carries a state and
  already-computed violation metadata only: no actor subject, no policy
  owner, id, or label, no raw usage row, no provider content, no request or
  response content, no secrets, and no PII. The service depends only on the
  resolver and the snapshot provider (the evaluator is a static call), and
  holds no reference to the completion service, the rate limiter, Redis,
  providers, repositories, controllers, or audit services. **It is not
  connected to live gateway traffic**: it is not a Spring bean, nothing in
  the completion or rate-limit path calls it, and it therefore has zero
  effect on real requests. **Concurrent enforcement is intentionally not
  implemented** — there are no Redis counters, request or token
  reservations, atomic check-and-consume steps, locks, or transactions around
  gateway completion, because two independent reads are not an atomic check.
  That belongs to the later runtime-enforcement milestone, together with
  deciding what `USAGE_UNKNOWN` and `INACTIVE` should mean for a caller.
  The atomic enforcement primitive those pieces need now exists as
  `GatewayUsagePolicyCounter`: `tryConsume(actorSubject, window, limit)`
  atomically decides whether consuming one request would stay within a
  limit, and returns whether the caller may proceed. It is the
  enforcement primitive only — it knows an actor, a window, and a number,
  and has no notion of `GatewayUsagePolicy`, policy resolution, or policy
  interpretation, which belong to a later service. It exists because the
  persisted-usage snapshot is inherently after the fact: two requests
  arriving together can both read the same historical count and both
  conclude there is room, so deciding from that snapshot and then admitting
  would let both through. This counter instead performs the count and the
  decision as **one indivisible operation**, so the count a request is
  admitted against already includes every request admitted before it,
  including ones still in flight. Adoption is all-or-nothing: `true` means
  the unit was consumed and the request may proceed, `false` means it was
  not consumed; rejected attempts deliberately do not increment, so a
  client hammering a spent limit cannot inflate the count it is already
  over. Only the two **request** limits are supported —
  `GatewayUsagePolicyCounterWindow.MINUTE` (the current UTC minute) and
  `DAY` (the current UTC calendar day), fixed UTC windows, never a rolling
  window and never the JVM default zone. The comparison is exactly
  `currentCount + 1 <= limit`, so a limit is the highest permitted value:
  the first request under a limit of 1 is admitted, the second is rejected,
  and with a limit of n exactly n requests are admitted. Two
  implementations sit behind the abstraction: the default process-local,
  thread-safe `InMemoryGatewayUsagePolicyCounter` (one atomic
  `ConcurrentHashMap.compute` per attempt, deterministic for local
  development) and `RedisGatewayUsagePolicyCounter`, **intended for
  multi-instance enforcement** because every instance consults the same
  counter, selected by `aegivault.gateway.policy-counter` (`IN_MEMORY`
  default, `REDIS` optional) — a switch separate from, and independent of,
  the global rate limiter's `aegivault.gateway.rate-limiter`. The Redis
  implementation issues **one atomic Lua execution per consume** (INCR,
  TTL on first creation, and the limit compare inside the same script),
  never separate GET/INCR/EXPIRE round trips. Counters live at
  `aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>`,
  namespaced so they can never collide with the rate limiter's
  `aegivault:gateway:rate-limit:<actorSubject>` keys, and each key carries a
  TTL covering the rest of its window plus a small clock-skew grace, so Redis
  expires it with no background cleanup job. It **fails closed**: any Redis
  failure or empty script result raises
  `GatewayUsagePolicyCounterUnavailableException` with a fixed safe message
  and never admits the request, leaking no host, port, key, counter, actor
  subject, or underlying exception text; no HTTP status is chosen yet.
  **This counter is not wired into gateway traffic** — no gateway path calls
  it, so it has zero effect on real requests and policies remain
  unenforced. **`tokensPerDay` is intentionally not implemented**: token
  usage is only known after a provider response, while request admission
  happens before provider invocation, so there is no truthful token number
  to compare at admission time. No estimated token count, max-token
  assumption, character-to-token conversion, or response-size heuristic is
  used anywhere; token enforcement requires an explicit
  reservation/accounting design in a later milestone.
  The runtime enforcement layer over that counter now exists as
  `GatewayUsagePolicyEnforcementService`. It resolves the actor's effective
  policy and, for each **configured request limit**, calls
  `GatewayUsagePolicyCounter.tryConsume(actor, window, limit)` — minute limit
  against `MINUTE`, day limit against `DAY` — where the atomic counter call
  *is* the admission decision. It never reads a count and compares in Java
  first; that read-then-write split is exactly the race the counter exists to
  close. `GatewayUsagePolicyEvaluator` is deliberately **not** used for
  admission: it evaluates a persisted, after-the-fact snapshot, so two
  simultaneous requests would both read the same count and both pass. The
  evaluator remains the tool for observational, post-hoc policy decisions.
  All configured request limits must admit the request, evaluated in a fixed,
  deterministic order (day, then minute), and a null limit is unconstrained
  and skipped, so a minute-only policy never touches the day counter. The
  first limit that rejects short-circuits the rest, and a counter failure
  stops the call immediately.
  Its result is a `GatewayUsagePolicyEnforcementResult` with four states:
  `NO_POLICY`, `ALLOW`, `REJECTED`, and `INACTIVE`; a `REJECTED` always names
  the rejecting window (`MINUTE` or `DAY`), and a `REJECTED` without a window
  — or a window on a non-rejection — is rejected at construction. `NO_POLICY`
  is kept distinct from `ALLOW` for the same reason as in the observational
  decision service, and **no capacity is consumed** in the no-policy,
  inactive, or ambiguous cases: spending an allowance for a request that no
  limit governs would silently shrink the actor's real allowance. Ambiguous
  configuration still propagates `GatewayUsagePolicyAmbiguousException`
  unchanged rather than being converted into a rejection.
  **A counter failure is not a rejection.** It propagates
  `GatewayUsagePolicyEnforcementException` with a fixed safe message, and no
  result object is produced at all, so "could not check" can never be
  mistaken for "limit exceeded"; it fails closed.
  **Multi-window consumption is now atomic.** The counter contract accepts a
  typed `GatewayUsagePolicyCounterRequest` (actor, the caller's instant, and
  per-window limits) and answers with a `GatewayUsagePolicyCounterResult`
  (`ALLOWED`, or `REJECTED` naming the window). All requested windows are
  decided as one indivisible operation: if every window has room, **all** of
  them are incremented; if any window is exhausted, **none** is. A refused
  request therefore costs the actor nothing in any window, which removes the
  earlier partial-consumption case where a request rejected on the minute limit
  had already spent a unit of the day's allowance. `IN_MEMORY` gets this from a
  single per-actor atomic state boundary — one `ConcurrentHashMap.compute`
  covers the rollover check, every capacity check, and every increment, and it
  never issues two independent computes for one request. `REDIS` gets it from
  one Lua invocation for the whole attempt: a read-only check loop over every
  requested key returns on the first exhausted window before writing anything,
  and a second loop increments every key only when all had room; TTLs are
  applied only to newly created keys. The script returns `n` for an admission
  and `-i` for a rejection, the sign keeping a rejection at the last position
  from ever reading as an admission. A rejected window is reported in the fixed
  `evaluationRank()` order (day, then minute) — never derived from configured
  limit values, map iteration order, or timestamps. The single-window
  `tryConsume(actor, window, limit)` operation is preserved and delegates to
  the same path, so its `currentCount + 1 <= limit` boundary is unchanged. The
  key format `aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>`
  is unchanged by this contract upgrade, as is the global rate limiter's
  separate namespace. The enforcement service now builds one atomic request
  from the resolved policy's configured `requestsPerMinute` /
  `requestsPerDay` and calls the counter once, mapping the single result onto
  its existing `NO_POLICY` / `ALLOW` / `REJECTED` / `INACTIVE` states, with
  ambiguity propagation and fail-closed behaviour unchanged.
  **`tokensPerDay` remains unenforced**: it is never read by the enforcement
  service, and there is no token reservation in the request path, no
  pre-request token check, no response token rollback, and no character
  heuristic.
  **The token-budget reservation primitive now exists, as infrastructure
  only.** `GatewayTokenBudget` is a separate abstraction from
  `GatewayUsagePolicyCounter` and from the global rate limiter: it answers "does
  this actor have room in its daily *token* budget", not "may this actor make
  this request". Its single operation is
  `tryReserve(actorSubject, windowStart, limit, requestedTokens)`, which
  atomically decides whether the reservation fits and performs it. For one
  actor's UTC day the attempt succeeds exactly when
  `usedTokens + reservedTokens + requestedTokens <= limit`; a success adds
  `requestedTokens` to the held total and a failure adds **nothing**, so a
  rejected attempt consumes no capacity and hammering a spent budget cannot
  push the day's total further over. Adoption is all-or-nothing — the full
  amount is held, never part of it. The result is an immutable
  `GatewayTokenBudgetReservation` with exactly two states, `RESERVED` and
  `REJECTED`: it carries only the state, the `reservationId`, and the
  `reservedTokens` that one reservation holds, and deliberately exposes no
  current total, no remaining tokens, no limit, no window, no Redis key, and no
  actor subject, so a rejection cannot tell a caller how much budget is left.
  **The requested amount is supplied by the caller and is not usage.** Nothing
  here estimates it: there is no character-to-token conversion, no response-size
  guess, no max-token assumption, and no model-specific formula anywhere in the
  package. Deciding what a future request should reserve is a separate,
  unanswered question.
  Two implementations sit behind the abstraction. `InMemoryGatewayTokenBudget`
  is process-local and gets atomicity from a **single
  `ConcurrentHashMap.compute` per attempt** keyed by actor *and* day, with the
  read, the capacity check, and the reservation all inside that one map
  operation — never two computes for one attempt — and shares no state, no map,
  and no window arithmetic with the request counter or the rate limiter.
  `RedisGatewayTokenBudget` is intended for multi-instance enforcement and
  performs **one Lua execution per reservation**: the read, the comparison, and
  the writes all happen server-side, and the comparison returns before any
  write, so a rejection creates no key and no TTL. It is never a GET, a
  Java-side compare, and an INCR. Budgets live at
  `aegivault:gateway:token-budget:<windowStart>:<actorSubject>` (for example
  `aegivault:gateway:token-budget:1773532800:actor-1`), a **new namespace that
  is distinct from the request-policy counters
  (`aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>`)
  and the global rate limiter (`aegivault:gateway:rate-limit:<actorSubject>`)**,
  so the two gateway mechanisms can never corrupt each other's state; each
  existing namespace is unchanged. The day is the same fixed UTC calendar day
  the request counter already uses, derived identically, so a new day is a new
  key rather than a reset, and the key's TTL covers the rest of that day plus a
  one-second clock-skew grace so Redis expires it with no cleanup job. The
  Redis budget **fails closed**: any Redis failure, or an empty or
  unrecognised script result, raises `GatewayTokenBudgetUnavailableException`
  with a fixed safe message and never reserves, leaking no host, key, actor,
  token count, or underlying exception text. An outage is deliberately not
  reported as a rejection: "the budget is spoken for" and "the budget is
  unknown" must not read as the same answer. Invalid input is refused before any
  state is read or written — a blank actor, a null or non-midnight-UTC window
  start, a non-positive limit, and a non-positive requested amount are all
  programming errors, not budget outcomes.
  **Reservations can now be reconciled against actual provider usage**, via
  `reconcile(actorSubject, windowStart, reservationId, actualTokens)`. A
  reservation is held capacity, not a measurement, so settling it moves the day
  from one to the other exactly as
  `newAccountedTotal = existingAccountedTotal - reservedTokens + actualTokens`:
  the reservation is **removed** from the day's reservation set and the
  provider's real figure takes its place as settled usage, so `reserved 100,
  actual 80` leaves the day owing 80 and `reserved 100, actual 20` releases 80
  back into the day's capacity. The amount subtracted is always what *that
  reservation* holds, read from the budget rather than supplied by the caller,
  so a caller cannot credit the day with a release it never made.
  **Actual usage is never clamped to the reservation.** Provider usage is a
  post-provider accounting fact: if `reserved 100, actual 140`, the
  reconciliation **succeeds** and the day records 140, because recording 100
  would understate what the provider really cost. When that pushes the day's
  total past the configured limit, the day is simply left overspent and
  **every subsequent reservation is rejected** by the same capacity boundary as
  always — the over-use is neither silently absorbed nor treated as a failure.
  `actualTokens == 0` is **accepted**, not rejected: a provider can legitimately
  report no tokens, so zero is a real measurement, whereas a negative amount is
  impossible and would hand capacity back on every call.
  **Reservations are single-use, and a reservation id names exactly one
  reservation within one actor's day.** A successful reconciliation consumes
  the reservation, so reconciling the same id again fails exactly like an
  unknown id, and a duplicated or retried callback can never settle the same
  tokens twice. An unknown, already-settled, or cross-actor/cross-day id is one
  indistinguishable `GatewayTokenBudgetReservationStateException`, which is what
  prevents one actor from settling another's reservation and prevents a caller
  from probing whether an id exists somewhere else. That exception is
  deliberately distinct from `GatewayTokenBudgetUnavailableException`: a bad or
  absent reservation is a caller-state failure, while an unreachable or
  unusable store is an infrastructure outage, and collapsing the two would make
  an outage look like ordinary traffic. Its result is the minimal
  `GatewayTokenBudgetReconciliation`, whose entire surface is the single `state`
  of `RECONCILED` — no total, no remaining capacity, no limit, no actor, no day,
  no Redis key, and no internal reservation state, so a settled amount can never
  be read back out as a capacity oracle. Reconciliation keeps the same atomicity
  guarantees: `IN_MEMORY` performs the lookup, the removal, and the usage
  adjustment inside the **same single per-actor-and-day
  `ConcurrentHashMap.compute`** a reservation uses — never a second compute for
  one settlement — so a racing reservation sees either the state before the
  settlement or the fully settled state after it, and `REDIS` does the whole
  thing in **one Lua execution** that locates the reservation, returns before
  any write when it is absent, and otherwise removes the field and adjusts the
  total, deliberately not touching the TTL so the day's own expiry is
  preserved. It is never a GET, a Java-side compare, and then an HDEL/INCR.
  **This is still infrastructure only.** No gateway path calls `reconcile`, no
  completion service or enforcement service references it, and nothing reserves
  before a provider call or settles after one, so `tokensPerDay` remains
  entirely unenforced and no provider request is ever rejected on token grounds.
  **The input to a future reservation is now defined, and is deliberately
  caller-supplied.** `GatewayTokenBudgetReservationRequest` is a one-field
  immutable record carrying the **requested reservation amount**: the number of
  tokens a caller explicitly asks the budget to hold, and nothing else. It adds
  no second input such as a prompt length, model name, or output cap, so there
  is no way to express an implicit amount through it. The amount is validated
  once, at construction: it must be present and strictly positive, with zero and
  negative figures refused as contradictions rather than small reservations, and
  an absent amount rejected distinctly from a zero one. It is **not** actual
  provider usage — that is still obtained only after a provider response and is
  already accounted for by `reconcile` — and it is **not** an estimate, an
  inferred token count, a response size, a character count, or a byte count.
  Nothing derives it: there is no tokenizer, no character- or byte-to-token
  conversion, no model-specific formula, no pricing or billing table, no
  max-token assumption, and no heuristic or fallback anywhere. This is a
  standing decision rather than a gap — a fabricated token count is a number the
  system cannot stand behind, and a silently wrong one would make the budget
  itself untrustworthy, so the amount stays explicit and the question of what a
  real request should reserve remains open until it can be answered honestly.
  The type is a **standalone contract**: it is not a parameter of
  `GatewayTokenBudget`, no implementation accepts it, and no other production
  type in the package references it, so it is not yet a call path and performs
  no I/O. It adds no decision or enforcement state — no `ALLOW`/`REJECTED`
  budget response, no HTTP status, and no token policy outcome — because
  choosing what a request reserves and deciding what a rejection means are
  decisions for the enforcement layer, not for this input.
  **This primitive is not wired into gateway traffic.** No gateway path calls
  it, no completion service or enforcement service references it, and it is not
  a Spring bean, so `tokensPerDay` remains entirely unenforced and live
  `tokensPerDay` enforcement is still pending a later milestone.
  **Usage-policy request limits are now enforced on live gateway completions.**
  `GatewayCompletionService` calls the enforcement service for the
  JWT-derived actor, in a fixed order: the global `GatewayRateLimiter` runs
  **first**, then persistent policy request-limit enforcement, and only then
  request inspection, inspection audit, provider selection, provider
  invocation, response inspection, and usage recording. A global rejection
  therefore never reaches the policy check and spends no policy capacity; a
  policy rejection never reaches anything downstream. Because the two controls
  are independent they are never merged and never compensate for one another,
  and each keeps its own message: a global rejection stays HTTP 429
  `Gateway rate limit exceeded.`, while a policy rejection is HTTP 429
  `Gateway usage policy limit exceeded.` — a distinct body that names no
  rejected window, actor, policy id, counter, limit, or Redis detail.
  `NO_POLICY`, `INACTIVE`, and `ALLOW` all continue through the existing flow
  unchanged. A policy-counter outage is **fail-closed** and distinct from a
  rejection: it surfaces as HTTP 500 `Unable to enforce gateway
  policy.`, never as a 429, because an outage is not an exceeded limit.
  Ambiguous policy configuration is likewise HTTP 500
  `Unable to resolve gateway usage policy.`, choosing no policy and exposing
  no policy id, owner, or candidate count.
  **Policy decisions are now auditable, under their own event types.** A quota
  decision is not a security verdict, so it never reuses
  `AI_GATEWAY_INSPECTION_ALLOWED` or `AI_GATEWAY_INSPECTION_BLOCKED`; the
  ledger instead records `GATEWAY_USAGE_POLICY_ALLOWED` or
  `GATEWAY_USAGE_POLICY_REJECTED` under resource type `GATEWAY_USAGE_POLICY`,
  with the resolved policy UUID as the resource id and hand-built,
  fixed-field-order metadata only: `{"decision":"ALLOW","enforcedWindows":[...]}`
  or `{"decision":"REJECTED","rejectedWindow":"MINUTE"}`. The metadata never
  contains the actor (the ledger stores it as its own column), the policy owner
  or label, a configured limit, a usage count, a counter value, a Redis key, or
  any request content, provider output, PII, or secret. An admitted request
  records its policy event *before* inspection and then its ordinary
  inspection event, so one request can legitimately hold two entries for two
  different decisions; a policy-rejected request records only its policy
  event, because it is never inspected. `NO_POLICY` records **nothing** — no
  policy was consulted, so there is no decision to evidence — and `INACTIVE`
  records nothing either, which is a deliberate choice: a disabled policy is
  not applied, so an `ALLOWED` event would claim a quota check that never ran,
  leaving `ALLOWED` in the ledger meaning exactly "an enabled policy admitted
  this". A policy-audit failure is **fail-closed** as HTTP 500
  `Unable to record gateway policy audit event.`, and for a refused request it
  replaces the 429: a rejection whose evidence was not stored must not be
  reported as though it had been. A policy-rejected request records no usage
  row, because there was no provider call.
  The completion service depends only on the enforcement service, never on
  the policy repository, the counter, a counter implementation, Redis, or the
  evaluator. This is **request-limit enforcement, not token-budget
  enforcement**: `tokensPerDay` is still unenforced, and a policy declaring
  only `tokensPerDay` is admitted without consuming request capacity (and is
  recorded with an empty `enforcedWindows`).
  **Policy definition changes are auditable too, under their own event
  types.** A lifecycle mutation and a runtime enforcement decision are
  different facts, so the ledger records `GATEWAY_USAGE_POLICY_CREATED`,
  `GATEWAY_USAGE_POLICY_UPDATED`, and `GATEWAY_USAGE_POLICY_DELETED` for
  changes to the policy row and never reuses
  `GATEWAY_USAGE_POLICY_ALLOWED` / `GATEWAY_USAGE_POLICY_REJECTED`, which
  describe a quota decision about one request. All of them share the resource
  type `GATEWAY_USAGE_POLICY` and the policy UUID as the resource id, so a
  single policy's whole history can be followed by id. The actor is the
  verified JWT subject, stored in the ledger's own column and never repeated
  into the event data. The metadata is **intentionally minimal** and
  deterministic — one closed-vocabulary field, `{"action":"CREATED"}` /
  `{"action":"UPDATED"}` / `{"action":"DELETED"}` — because the ledger must
  prove that the policy changed, not copy it: no label, description,
  request/token limits, enabled state, `ownerSubject`, raw request body,
  Redis key, counter, usage data, prompt/response content, PII, or secret
  ever enters an event.
  The append happens strictly **after** the mutation is durable (the service
  transaction has committed), so a rejected create or a foreign/missing
  update or delete appends nothing at all — there is never an event for an
  operation that ultimately failed. If the append then fails, the endpoint
  fails closed as HTTP 500 `Unable to record gateway policy audit event.`
  (no SQL detail, hash, actor, policy id, or exception text). That is
  **fail-closed but not compensating**: the mutation that already committed is
  deliberately *not* rolled back, and a deleted policy is never recreated to
  make its audit entry succeed — there is no compensation transaction. The
  honest consequence is a real failure window: a committed mutation may exist
  with no ledger entry, and the 500 is how that gap is reported. Runtime
  policy-enforcement auditing (`GATEWAY_USAGE_POLICY_ALLOWED` /
  `GATEWAY_USAGE_POLICY_REJECTED`) and inspection auditing
  (`AI_GATEWAY_INSPECTION_ALLOWED` / `AI_GATEWAY_INSPECTION_BLOCKED`) are
  unchanged by any of this.
  **Policy audit history is now queryable by the authenticated owner.**
  `GET /api/gateway/policies/{policyId}/audit` returns the caller's own
  history for one policy as `{"entries":[{"eventType","resourceId",
  "eventData","createdAt"}]}`, at most the newest **100** entries, newest
  first (`createdAt` descending, then `sequenceNumber` descending). One
  derived repository query does the whole job — filter on resource type,
  resource id, **and** the JWT actor subject, restrict the event types, order
  deterministically, and apply the 100-entry bound database-side so an
  unbounded history is never loaded into Java. The actor filter is mandatory
  because the resource id comes from the URL: there is no by-resource-id-only
  read, no ADMIN bypass, and no cross-user reporting.
  **History works even after the policy is deleted**, because the query reads
  only the ledger and never the policy table — the ledger, not the policy
  row, is the historical record. A foreign or unknown policy id is an empty
  `200`, not a `404`, so the endpoint cannot be used to probe whether
  someone else's policy exists.
  Only policy-specific events come back: the three lifecycle types and the
  two runtime enforcement types. Inspection events
  (`AI_GATEWAY_INSPECTION_ALLOWED` / `_BLOCKED`) and every unrelated event
  are excluded by the query itself. `eventData` is returned **verbatim** as
  the policy audit events wrote it — the layer never enriches a record with a
  policy name, description, limit, enabled state, owner, current usage,
  counter, Redis key, prompt, provider response, PII, or secret, and never
  reconstructs historical policy state from current CRUD data.
  Each entry exposes only those four safe fields: the chain `sequenceNumber`,
  `previousHash`, `entryHash`, the `actorSubject`, and internal database
  identifiers are all withheld. **Cryptographic verification remains a
  separate concern** — `GET /api/audit/verify` is unchanged, and this
  endpoint is a scoped read projection, not a verifier. There is no
  pagination, and this is still **not token-budget enforcement**:
  `tokensPerDay` remains unenforced and no enforcement behavior changed.
  There is
  no admin or cross-user usage reporting, and no budget, quota, cost,
  or accounting enforcement exists yet. No external cloud provider exists. No response redaction or rewriting exists: blocking
  is the only response action. Gateway completions are additionally
  rate-limited per authenticated actor before any inspection or provider
  work: the completion service spends one `GatewayRateLimiter` attempt
  (keyed by the verified JWT subject only) first, and a rejected actor
  fails as HTTP 429 with the safe `Gateway rate limit exceeded.` message
  — never a BLOCK verdict, which stays HTTP 200 data, and with no
  inspection audit entry. `GatewayRateLimiter` is now an abstraction with
  two implementations behind the same fixed-window policy (20 requests
  per actor per 1-minute window, keyed by the verified JWT subject
  only): the default process-local in-memory `InMemoryGatewayRateLimiter`
  (expired windows evicted lazily, no Redis, no scheduler — not shared
  between instances) and the optional `RedisGatewayRateLimiter` for
  multi-instance enforcement, selected by `aegivault.gateway.rate-limiter`
  (`IN_MEMORY` default, `REDIS` optional). The Redis limiter counts per
  actor at `aegivault:gateway:rate-limit:<actorSubject>` through one
  atomic Lua execution per attempt (increment, window TTL on first
  creation, limit compare — no separate GET/INCR/EXPIRE trips, no
  background scheduler) and fails closed: a Redis outage answers a
  generic 500 without leaking Redis details instead of bypassing the
  limit. No production-scale distributed guarantees are claimed beyond
  this single atomic counter.

### Token-budget reservation primitive (infrastructure only)

`tokensPerDay` is still **not enforced**, and the primitive that will make it
safe to enforce now exists as `GatewayTokenBudget` in
`gateway.policy.budget`. It is **infrastructure only**: it is not a Spring bean,
nothing in the gateway completion path, the rate limiter, the request-policy
counter, provider selection, or the audit ledger calls it, and no live request
is admitted or rejected by a token amount. It exists because actual provider
token usage is known only *after* a provider response, so a pre-request
"read the current total, compare, then admit" is unsafe under concurrency — two
requests arriving together would both read the same total and both conclude
there is room. The primitive therefore reserves explicitly instead of
measuring.

Its single operation is `tryReserve(actorSubject, windowStart, limit,
requestedTokens)`, which atomically decides whether `usedTokens +
reservedTokens + requestedTokens <= limit` holds for that actor's current UTC
day, and either reserves the whole requested amount or nothing. Adoption is
all-or-nothing: a partial amount is never reserved, and a rejected attempt adds
nothing, so hammering a spent budget cannot push the day's total further over.
**`requestedTokens` is supplied by the caller and is never inferred here** —
there is no character-to-token estimation, response-size heuristic,
model-specific token formula, or max-token guess anywhere in this package,
because a fabricated token number would enforce a limit against fiction. How a
future gateway request arrives at a reservation amount is a separate decision
this milestone deliberately does not make.

The result is a small immutable `GatewayTokenBudgetReservation` with exactly
two states, `RESERVED` and `REJECTED`. A successful result carries only the
`reservationId` and `reservedTokens` — the **minimum** contract a later
reconciliation step needs to settle a reservation against real provider usage.
It deliberately exposes no current token count, no remaining or available
tokens, no limit, no window, no Redis key, no actor subject, and no storage
detail, so a rejection cannot tell a caller how much budget is left. Settling,
releasing, and refunding a reservation are **not** implemented; settled usage
(`usedTokens`) is currently always zero because no reconciliation step exists
yet, and nothing pretends to have already accounted for it.

The window is a single fixed UTC calendar day, `dayStart <= usage <
nextDayStart`, using the same semantics as the request-policy counter's `DAY`
window and never a rolling 24 hours or the JVM default zone. A non-midnight
UTC `windowStart` is rejected before any state is touched, because a partial-day
start names no real UTC day and would silently split one day's budget in two.

Two implementations sit behind the abstraction, both concurrency-safe. The
default process-local `InMemoryGatewayTokenBudget` gets its atomicity from a
**single per-actor-per-day state boundary**: one
`ConcurrentHashMap.compute` covers the read, the capacity comparison, and the
reservation, with no check-then-act gap and no second compute per attempt, so
simultaneous reservations can never overshoot the limit. `RedisGatewayTokenBudget`
is intended for multi-instance enforcement and performs **one Lua invocation per
reservation** — the read, the comparison, the total write, the per-reservation
field, and the TTL are all server-side in that single script, never a separate
`GET` → Java comparison → `INCR`. The atomicity claims are asserted on the
shipped Lua text itself, not only on test doubles.

Redis budgets live at
`aegivault:gateway:token-budget:<windowStart>:<actorSubject>`, for example
`aegivault:gateway:token-budget:1773532800:actor-1`, where `windowStart` is the
day-start epoch second. This is a **new namespace of its own** and is not shared
with the request-policy counters
(`aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>`) or the
global rate limiter (`aegivault:gateway:rate-limit:<actorSubject>`), both of
which are unchanged: the three guard different quantities of different things,
and sharing a key would let one silently corrupt another. Each day is one Redis
hash — a `total` field plus one `reservation:<id>` field per outstanding
reservation, so a later reconciliation can find what a given id reserved exactly
as the in-memory budget can — carrying a TTL that covers the rest of its day
plus a one-second clock-skew grace, so Redis expires it with no cleanup job and
a new day is a new key rather than a reset of an old one.

Redis failure **fails closed** as `GatewayTokenBudgetUnavailableException` with
the fixed message `Unable to reserve gateway token budget.`, leaking no Redis
host, port, key, actor, token count, limit, or underlying exception text (the
cause is kept for server logs only). An empty or unrecognised script result
fails closed the same way rather than being read as either outcome, because
"could not determine the budget" is not "the budget is spent" and is certainly
not "there is room". Blank actor subjects, null or non-midnight day starts,
non-positive limits, and non-positive requested amounts are all rejected before
any state is read or written and before any round trip is spent. No HTTP status
is chosen, because there is no controller or gateway integration yet.

**Live `tokensPerDay` enforcement remains pending**: this primitive does not
reject or block any request, and no token-usage audit event, dashboard, refund,
or post-provider reconciliation exists.

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
