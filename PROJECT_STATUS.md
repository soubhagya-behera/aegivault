# Aegivault Project Status

## Completed

* Spring Boot backend generated with Spring Initializr.
* Java 17.
* Spring Boot 4.1.1.
* Maven (wrapper included, no separate install needed).
* PostgreSQL dependency and local configuration foundation.
* Spring Data JPA (`ddl-auto=validate`; Hibernate never modifies the schema).
* Flyway dependency, enabled, pointed at `db/migration/`.
* Spring Security dependency (no custom configuration yet).
* Validation starter.
* Actuator starter.
* `application.properties` for local configuration (real credentials).
* `application-example.properties` for safe repository configuration
  (placeholders only).
* Local `application.properties` excluded from Git via `backend/.gitignore`.
* Initial Git repository created.
* Initial backend commit pushed to GitHub.
* V1 datasets migration (`datasets` aggregate-root table).
* Dataset JPA entity mapped 1:1 to the Flyway schema.
* Dataset repository with per-owner listing.
* PostgreSQL persistence test (`@DataJpaTest` against the real database).
* Flyway → JPA validation path proven (migration applies, `validate` passes).
* V2 auth migration (`users`, `roles` seeded with USER/ADMIN, `user_roles`).
* Identity entities (`User` implementing `UserDetails`, `Role`) plus
  `UserRepository.findByEmail` and role lookup.
* Password registration (`POST /api/auth/register`) and login
  (`POST /api/auth/login`) with BCrypt strength 12 and lowercase email
  normalization; duplicate email rejected with 409.
* HMAC SHA-256 JWTs (60-minute expiry, `sub`/`email`/`roles` claims) issued
  via Spring Security's `JwtEncoder`, verified by `JwtDecoder`.
* Stateless security configuration: JWT Bearer resource server, CSRF
  disabled, `/api/auth/**` and actuator health/info public, everything else
  authenticated, `roles` claim mapped to `ROLE_USER`/`ROLE_ADMIN`, method
  security enabled.
* Authenticated profile endpoint (`GET /api/auth/me`).
* Auth test suites (repository + API, 10 tests) against real PostgreSQL.
* Owner-scoped dataset API (`POST /api/datasets` returning 201 + `Location`
  with server-set `CSV`/`UPLOADED`/trimmed name, `GET /api/datasets` listing
  only the caller's rows, `GET /api/datasets/{id}` via a single
  `findByIdAndOwnerSubject` lookup with identical 404 for other-owner and
  missing ids and 400 for malformed UUID; no `ownerSubject` in responses).
* Dataset API test suite (13 tests) against real PostgreSQL: USER/ADMIN
  behave identically with no cross-user access.
* PII detection foundation (`pii` package: `PiiType` enum, immutable
  `PiiDetection` carrying only the type, minimal `PiiDetector` contract
  `Optional<PiiDetection> detect(String)`, and `PiiDetectorRegistry`
  orchestrating all detectors via Spring `List<PiiDetector>` injection
  with deterministic `PiiType`-ordered, deduplicated results).
* Eleven conservative whole-value PII detectors: `EmailDetector` (practical
  email subset, whole value only), `PhoneDetector` (scoped to supported
  Indian/international-style mobile formats), `CreditCardDetector`
  (normalized 13–19 digits with Luhn checksum), `IpAddressDetector`
  (strict IPv4 dotted-decimal plus IPv6 literal forms, no DNS/network
  calls), `UuidDetector` (strict 8-4-4-4-12 hexadecimal textual form),
  and `ApiKeyDetector` (only explicit `sk-`/`sk-proj-`, `ghp_`/`gho_`/
  `ghu_`/`ghs_`/`ghr_`, and labelled `api_key`/`apikey`/`api-key`
  patterns; bare alphanumeric strings are never classified as keys),
  plus `PasswordDetector`, `JwtDetector`, `PersonNameDetector`,
  `AddressDetector`, and `CustomIdentifierDetector` (all conservative
  whole-value policies; see Current state).
  Detection results never expose raw sensitive values.
* Schema-aware PII profiling foundation plus CSV schema discovery and
  bounded CSV profiling: see Current state below.
* CSV schema discovery and bounded CSV profiling (`dataset.csv`):
  `CsvTokenizer` (RFC 4180-style quoting, `""` escapes, delimiters and line
  breaks inside quoted fields, LF/CRLF/CR terminators, strict quote rejection,
  per-field length ceiling, per-record column ceiling, one record at a time so
  no record list accumulates), `CsvDiscoveryService` (first record is the
  header, verbatim column names, blank/duplicate header rejection, exact
  row-width validation, all-blank record skipping, UTF-8 with BOM tolerance,
  byte-bounded input, sampled-row ceiling, explicit rows-read vs rows-retained
  accounting), `CsvLimits` (four explicit safety limits with conservative
  defaults), `CsvSchema`/`CsvSample` (immutable discovery results holding no
  claimed full-dataset counts) and `CsvDatasetProfiler` (CSV → `ColumnInput` →
  `DatasetProfiler` → `DatasetProfile`, performing no sanitization, no
  transformation, no persistence, and no external, network, cache, or AI call).
* CSV discovery/profiling test coverage (pure unit tests, no Spring/DB/network):
  86 tests covering parser behavior, header policy, duplicate-header rejection,
  row-width policy, malformed quoting, safety limits, bounded sampling,
  determinism, error safety, and CSV→profile integration.
* Pure unit test totals: 497 tests (249 PII/profile + 86 CSV discovery/profiling + 77 sanitization
  + 34 CSV sanitization pipeline + 28 run domain + 5 run request + 6 audit hash + 12 gateway inspection),
  0 failures, 0 errors, 0 skipped.
* Repository total: 762 tests, 0 failures, 0 errors, 0 skipped — 485 pure unit
  tests plus 265 context/persistence/API/lifecycle/execution tests run against the real local
  PostgreSQL.
* Stored dataset profiles (`dataset.profile`, V8): normalized
  `dataset_profiles` / `dataset_profile_columns` /
  `dataset_profile_detections` tables (metadata only — no value, sample, or
  content column exists), `DatasetProfileService.saveProfile` /
  `getProfile` for owner-scoped write/read of an already-computed
  `DatasetProfile` with replace semantics that leave no stale rows, and
  `GET /api/datasets/{datasetId}/profile` returning the stored result
  verbatim (identical 404 for foreign, missing, and not-yet-profiled
  datasets; 401 unauthenticated; 400 malformed UUID). 11 new tests (6
  persistence round-trip + 5 API) against real PostgreSQL. Profiling is now
  triggered explicitly through `POST /api/datasets/{datasetId}/profile`
  (owner from the JWT subject; stored input opened through
  `DatasetInputSource`, profiled with the existing `CsvDatasetProfiler`,
  saved through `DatasetProfileService`, re-read for the response, with 12
  new API tests against real PostgreSQL). CSV upload and run creation are
  unchanged: upload stores bytes only and never profiles automatically. A
  read-only transformation preview is available through `GET
  /api/datasets/{datasetId}/profile/transformation-preview`, mapping the
  persisted profile through the existing default transformation policy
  (11 new API tests against real PostgreSQL; no policy, run, CSV, or audit
  changes). The preview suggestions can be persisted as a normal policy
  through `POST
  /api/datasets/{datasetId}/profile/transformation-preview/policy`
  (labels from the validated request, rules for detected types only with
  default strategies via the existing policy service — 12 new API tests
  against real PostgreSQL; no run, no execution, no dataset/profile/CSV
  change, no audit writes).
* Persistent sanitization policies (`sanitization.policy`, V6): owner-scoped
  reusable `SanitizationPolicy` aggregates with normalized
  `sanitization_policy_rules` rows, exposed through `POST /api/policies`
  (201 + `Location: /api/policies/{id}`),
  `GET /api/policies` (caller's rows only, newest first),
  `GET /api/policies/{policyId}` (identical 404 for foreign and missing
  ids), and `PUT /api/policies/{policyId}` (owner-only in-place replacement
  of labels and the full rule set: same id, `updatedAt` advanced, same
  response shape; earlier runs keep their frozen snapshots), and
  `DELETE /api/policies/{policyId}` (owner-only, 204 with no body; the
  policy and its rules vanish while earlier runs and their artifacts stay
  readable). The rule vocabulary is exactly the existing PII types and
  transformation strategies, `rules` are exposed as
  `{"piiType": "...", "strategy": "..."}` with no owner or persistence
  details, and nothing raw (PII, CSV) is stored. Run creation consumes a
  persisted policy: `POST /api/runs` takes
  `{"datasetId": "...", "policyId": "..."}`, requires the JWT subject to
  own both resources (foreign or missing ids share one generic 404), and
  freezes the policy's name/version/rules into the run's immutable
  `PolicySnapshot`; the inline-rules path is removed.
* Sanitized-artifact download API (`GET /api/runs/{runId}/artifact`): owner
  from the verified JWT subject only (never from parameters or body), bytes
  from the existing owner-scoped `SanitizationArtifactStore`, streamed
  straight to the response as `text/csv; charset=UTF-8` with
  `Content-Disposition: attachment; filename="sanitized-<runId>.csv"` — the
  filename derives from the validated run id only, never from dataset names,
  original filenames, policy labels, or owner values. Foreign runs, missing
  runs, unfinished runs, and completed runs without an artifact all return
  the same safe 404 body, alongside 401 unauthenticated and 400 malformed
  UUID; the artifact is served as stored (never re-parsed or re-sanitized),
  no second full in-memory copy is created, and the run state machine, the
  40 MiB bound, and every existing endpoint are unchanged. 11 new tests
  (10 API + 1 store-level completed-run gate).
* Tamper-evident audit ledger foundation (`audit`, V7): append-only
  `audit_ledger_entries` rows (1-based gapless `sequence_number` UNIQUE,
  `entry_hash` UNIQUE, safe-metadata `event_data` only) with server-derived
  SHA-256 over an explicit length-prefixed canonical form plus the previous
  hash (`GENESIS` for the first entry). `AuditLedgerService` appends one
  entry per transaction; `AuditLedgerVerificationService` replays the chain
  recomputing every hash and reports the first break as a value, with an
  empty ledger verifying valid. Run lifecycle events are now recorded by
  the run executor: `SANITIZATION_RUN_CREATED` after start plus
  `SANITIZATION_RUN_COMPLETED` / `SANITIZATION_RUN_FAILED` after the
  terminal transition commits (owner as actor, run id as resource,
  structural metadata only — exactly two entries per finished run). An
  audit append failure surfaces as a generic 500 without storage details
  and is never reported as success; there is no retry queue. Integrity is
  verifiable through authenticated `GET /api/audit/verify` (USER and ADMIN;
  verdict plus replayed-entry count plus failure code only when invalid —
  never ledger content). No other REST endpoints and no other
  integration yet — nothing else appends from other modules. Single-instance sequencing only:
  concurrent appends fail loudly on the UNIQUE constraint; no distributed
  locking by design (documented limitation).
* Security/integrity audit of the implemented input-upload → run → artifact flow
  (findings reported in that milestone's audit report). Two hardening fixes
  landed: the dataset input upload no longer reads the caller's CSV body inside
  a database transaction (only the owner check and the upsert are transactional,
  so a slow client can no longer pin a pooled connection and an open transaction
  for the length of its upload), and `CreateRunRequest` policy labels are now
  bounded at 255 characters like the dataset name instead of being unbounded
  client text stored on the run row and echoed in every run view. Both fixes
  carry focused regression tests (a transaction-scope probe over the upload
  path plus an overlong-label validator test and API rejection test). No other
  finding required a code change; the remaining observations are recorded as
  limitations rather than silently changed.
* Sanitization run domain and persistence (`sanitization.run`, V3 migration
  `sanitization_runs`): one operation record per run against one dataset —
  dataset FK (`ON DELETE RESTRICT`, history is never silently orphaned),
  denormalized owner for join-free owner-scoped reads, `RunStatus` state
  machine (`QUEUED -> RUNNING -> COMPLETED/FAILED`, terminal states final,
  illegal transitions rejected in the domain), immutable `PolicySnapshot`
  (canonical alphabetically-ordered JSON of the frozen type-to-strategy
  mapping, so a run never follows later policy changes), structural
  `RunResult` counts, metadata-only `RunFailure` (code/stage/message with
  length caps, never a `Throwable`), and `@Version` optimistic locking so
  concurrent lifecycle updates fail loudly. `SanitizationRunService`
  (`createRun`/`get`/`listByDataset`/`startRun`/`completeRun`/`failRun`)
  enforces dataset ownership with identical missing-vs-foreign responses for
  USER and ADMIN alike, and never invokes the CSV engine. 59 new tests
  (28 domain unit + 13 repository + 18 service, all green) cover transitions,
  snapshot determinism/immutability, owner isolation, CHECK constraints, FK
  behavior, stale-update rejection, and metadata-only failure storage. A
  synchronous success-path executor (`SanitizationRunExecutor`: create,
  start, run the existing `CsvSanitizationService` on caller-owned streams
  with the frozen plan, map `CsvSanitizationResult` counts into `RunResult`,
  complete) connects persisted runs to the engine with no new parsing,
  storage, or policy logic; documented-safe engine failures (`CsvParseException`
  as `CSV_PARSE_ERROR`, `MissingTransformationException` as `POLICY_GAP`,
  other `SanitizationException`s as `TRANSFORM_ERROR`) are persisted through
  the existing `failRun` transition instead of propagating, while anything
  else still propagates with the run left `RUNNING`. The first HTTP exposure
  exists (`GET /api/runs/{runId}`, thin controller over the owner-scoped
  `get`, hardened view only). No
  other REST endpoints, artifact storage, background workers, or audit ledger yet.
* API-key test fixtures initially resembled provider credentials closely
  enough to trigger GitHub secret scanning; the fixtures were rewritten so
  provider-like values are assembled from harmless fragments at test
  runtime, the API-key commit was amended, and the corrected history was
  pushed with `--force-with-lease`. The fixtures were always synthetic and
  no credential was revoked or rotated.

## Current state

* Backend foundation exists and boots against the local database; the
  context-load test passes.
* PostgreSQL database `aegivault` exists locally.
* V1 migration applied: `datasets` table plus Flyway history table.
* V2 migration applied: `users`, `roles` (USER/ADMIN seeded), `user_roles`.
* Two domain areas exist: `Dataset` (ingestion aggregate root) and identity
  (`User`, `Role`).
* Password authentication works (register/login return bearer JWTs);
  `owner_subject = users.id` (JWT `sub`) is enforced server-side by the
  dataset API; no dataset ownership endpoints are missing anymore.
* PII detector coverage is complete at eleven whole-value detectors: password (explicit label only), JWT structure, person-name heuristic, address heuristic, and custom-identifier allowlist join the six original detectors; the registry still auto-discovers detectors via Spring List injection with deterministic ordering and dedup.
* Schema-aware PII profiling foundation (pii.profile, not persisted, no REST changes): ColumnInput plus ColumnProfile carry only counts, PiiColumnProfiler aggregates registry detections over a deterministic bounded sample (default 100, first-N order; supplied vs analyzed counts recorded), and DatasetProfiler returns an immutable DatasetProfile with deterministic column ordering; detection rates use analyzed non-blank values as denominator and are observed rates only.
* CSV schema discovery and bounded CSV profiling now feed that foundation (dataset.csv, not persisted, no REST endpoint, no database writes, no sanitization): the CSV sample size defaults to the profiler's own sample size, rows read are reported separately from rows retained, all-blank data records are skipped, blank or duplicate header names and any row whose width contradicts the header are rejected with row/index/limit-only messages, and raw CSV values exist only in memory during processing (never logged, never persisted, never placed in a profile or in an exception).
* PII detection and CSV ingestion together still form a foundation only: individual whole-value detectors plus registry orchestration, column/dataset profiling, CSV discovery on caller-supplied input, the domain sanitization engine described below, and the end-to-end CSV sanitization   pipeline described below, and the persisted sanitization run lifecycle
  described below. Not completed yet:
  PostgreSQL schema discovery, dataset-wide PII scanning over stored data,
  persistence of profiles, findings, or plans, confidence scoring,
  Spring Batch processing, a multipart upload REST API, user approval workflow,
  stored versioned policies, or sanitization jobs.
* Known CSV-discovery limitations at this stage: the accepted input is buffered
  in memory (bounded by the 10 MB default input-byte limit) rather than being
  processed as a chunked/streaming pipeline; the full data-row count is reported
  only for the input actually supplied (no claim is made about a larger file the
  caller never handed over); all-blank records are skipped by policy rather than
  counted; and the configured limits bound the work of one discovery call but are
  not a complete denial-of-service protection.
* Data sanitization / transformation engine foundation (`sanitization`
  package, not persisted, no REST endpoint): detection and transformation are
  separate — detectors identify PII, an explicit immutable `TransformationPlan`
  maps each `PiiType` to one of six deterministic pure strategies (`KEEP`,
  `REDACT`, `MASK` with an explicit 4-character visible suffix, `SYNTHETIC_EMAIL`
  as `user-<token>@example.invalid`, `SYNTHETIC_PHONE` in the project's accepted
  phone format, unsalted `HASH_SHA256`), and `DataSanitizationService` applies the
  planned strategy via `TransformationRegistry` without detecting, reading CSV,
  or touching any repository, file, network, cache, or AI service.
  `ColumnSanitizer` sanitizes a column's sampled values into an immutable
  `SanitizedColumn` holding sanitized values plus policy metadata only (raw values
  never retained, never logged, never in exceptions). `DefaultTransformationPolicy`
  is an explicit application policy covering all eleven PII types, not a safety or
  compliance claim; hashing is deterministic pseudonymization-like transformation,
  not anonymization. 77 pure unit tests cover strategies, plans, the service,
  column sanitization, determinism, and the default policy.
* Sanitization run persistence exists (`sanitization.run`, V3
  `sanitization_runs`; operation metadata only, exposed through `POST /api/runs`,
  `GET /api/runs`, and `GET /api/runs/{runId}`). The CSV rewrite pipeline now exists as a
  domain/service-level operation only: `CsvSanitizationService` in `dataset.csv` orchestrates the
  existing `CsvTokenizer`, `PiiDetectorRegistry`, `DataSanitizationService`/`TransformationRegistry`,
  and a focused `CsvSanitizationWriter` to turn one caller-owned CSV `InputStream` plus an explicit
  `TransformationPlan` into a sanitized CSV `OutputStream` plus a structural `CsvSanitizationResult`.
  Detection and transformation stay separate, profile and sanitize share the same tokenizer and
  column/field limits, multiple detections resolve to the alphabetically-first `PiiType`, unmapped
  types and unregistered strategies fail closed, both streams stay caller-owned (flushed, never
  closed), and records stream one at a time within the same 10 MiB input-byte default (no row list,
  no sampling truncation). 34 pure unit tests cover headers, syntax, all eleven PII types, policy,
  deterministic multi-match resolution, relationships, structure, escaping, limits, and stream
  ownership.
* Audit ledger foundation exists (`audit`, V7) as persistence plus hash-chain
  integrity only: appends, replay verification, no REST endpoints, and no
  integration with other modules yet.
* AI gateway inspection foundation exists (`gateway`): deterministic
  request inspection reusing the existing `PiiDetectorRegistry` plus obvious-secret
  recognition through the existing API-key/JWT detectors, folded through a small
  block-on-PII/block-on-secrets policy into one ALLOW/BLOCK verdict with safe reason
  codes, exposed through authenticated `POST /api/gateway/inspect` (JWT subject as
  actor, server-generated id, fixed strict policy; BLOCK is 200 data). Every
  successfully inspected request appends exactly one metadata-only entry to the
  existing tamper-evident audit ledger (`AI_GATEWAY_INSPECTION_ALLOWED` /
  `AI_GATEWAY_INSPECTION_BLOCKED`; model, verdict, reason codes, detected type
   names — never request content, PII values, or secrets). No proxy,
   no LLM or network calls, no persistence of request bodies, no logging
   of request content; provider forwarding is still not implemented. A
    minimal LLM provider abstraction now exists (`gateway.provider`:
    `LlmProvider` plus immutable model/content `LlmRequest`/`LlmResponse`,
    where the response may additionally carry provider-reported usage
    metadata `LlmUsage` — prompt, completion, and total token counts, each
    null unless the provider supplied it; the mock always reports unknown
    usage and Ollama maps only its documented `prompt_eval_count` /
    `eval_count` fields, with unknown preserved as unknown and never
    estimated; ALLOW responses expose usage, BLOCK responses carry no
    provider payload and therefore no usage, and no budget/quota
    enforcement exists yet). Gateway provider usage is now persisted
    (`gateway.usage`, V9 `gateway_usage_records`: one metadata-only row
    per provider invocation that returned a response — server-generated
    request id, JWT-derived actor, model, exact provider-reported counts
    with unknown preserved as null, outcome `DELIVERED` or
     `SECURITY_BLOCKED`; never prompt/response content, PII, or secrets).
     Persisted usage has a read-only query layer
     (`GatewayUsageQueryService` over `GatewayUsageRepository` only):
     actor-scoped history newest-first, a database-side per-actor
     all-time aggregate, and an actor-scoped aggregate over an explicit
     UTC time window (`from <= createdAt < to`, computed database-side,
     null token totals preserved when unknown; groundwork for future
     governance and budget enforcement, while budgets and quotas are NOT
     implemented yet), exposed through the authenticated self-service API
     `GET /api/gateway/usage` (actor from the JWT subject only; at most
     the newest 100 records, newest first; usage metadata only; no
     admin/cross-user reporting, no budgets, no cost accounting yet).
     The windowed aggregate is now also exposed read-only at
     `GET /api/gateway/usage/aggregate?from=&to=`: both bounds are
     required ISO-8601 instants, the actor comes from the JWT subject
     only, the UTC half-open interval (`from <= createdAt < to`) is the
     query service's own, invalid or missing windows are safe 400s that
     never reach the database, and the response is one metadata-only
     aggregate object (record count plus token totals that stay null when
     unknown) with no history, pagination, budgets, or cost accounting.
     Gateway usage policies now also exist as persisted, owner-scoped
     *definitions* (`gateway.policy`: `GatewayUsagePolicy`, V10
     `gateway_usage_policies` — owner subject, label, optional
     description, nullable requests-per-minute / requests-per-day /
     tokens-per-day limits, an enabled switch, and timestamps; a limit is
     strictly positive or absent, and at least one must be supplied),
     managed over authenticated
     `POST/GET/PUT/DELETE /api/gateway/policies` with the owner taken only
     from the JWT subject and never from request data. **Policies are NOT
     enforced**: no gateway path reads the table, no counter is derived
     from it, and rate limiting remains controlled by `GatewayRateLimiter`
     configuration alone. Policies define request and token quantity
     limits only — budgets, pricing, billing, and cost accounting are
     still not implemented, and no daily or Redis counters exist.
     An explicit `GatewayUsagePolicyResolver` now defines how an actor's
     effective policy is resolved: candidates are only that actor's
     *enabled* policies, exactly one resolves, zero means no policy (a
     normal, non-error outcome), and two or more is ambiguous
     configuration that fails explicitly with a safe exception rather than
     silently picking newest, oldest, tightest, or loosest. The gateway is
     **not** policy-controlled yet: nothing in the traffic path calls the
     resolver, there is no policy activation or default-policy mechanism,
     and rate limiting is still governed solely by `GatewayRateLimiter`
     configuration.
     A pure `GatewayUsagePolicyEvaluator` now decides whether one
     already-collected usage snapshot satisfies one policy. It is a static,
     dependency-free function — no Spring bean, no repository, Redis,
     rate-limiter, controller, provider, or audit dependency — evaluating
     requests-per-minute, requests-per-day, and tokens-per-day limits
     (a null limit is unconstrained, and a limit is the highest permitted
     value, so exactly at the limit is allowed). It reports `ALLOW`,
     `LIMIT_EXCEEDED`, or `USAGE_UNKNOWN`, plus a distinct `INACTIVE` state
     so a disabled policy is never silently treated as satisfied. Unknown
     token usage is explicitly distinguishable: with a token limit
     configured but an untrustworthy total the result is `USAGE_UNKNOWN`,
     never `LIMIT_EXCEEDED` and never `ALLOW`, and the total is never read
     as zero. All tripped limits are reported together in a fixed
     declaration order. **The evaluator is not connected to gateway
     traffic** — nothing calls it and no request or token counters are
     collected for it — so policies remain unenforced.
     Policy evaluation now has a read-only persisted usage snapshot source
     (`GatewayUsagePolicyUsageSnapshotProvider`): for one actor and one
     supplied instant it reads current-minute and current-day usage with two
     windowed database-side aggregates. The current-minute and current-day
     request counts are counts of *persisted gateway usage records* — that
     is, recorded provider invocations that returned a response, not every
     inbound HTTP request: request-side BLOCKs, rate-limit rejections, and
     provider or selector failures write no usage row. Windows are UTC and
     half-open (`[start, start + 1 unit)`), never the JVM default zone. A
     daily token total is reported only when every matching row supplied
     one; unknown provider token usage propagates as unknown for the whole
     day rather than becoming a partial sum, with an empty day as the one
     known-zero case. The provider is internal and read-only, is not yet
     connected to enforcement, and no policy is enforced.
     Policy resolution, usage snapshot collection, and evaluation are now
     composed into one decision service
     (`GatewayUsagePolicyDecisionService`), which answers "what is this
     actor's current gateway usage policy decision right now?" by resolving
     the policy, reading the snapshot for the same actor at the caller's
     supplied instant, and evaluating the one resolved policy against that
     one snapshot. It adds no new evaluation logic — every limit comparison
     and the violation order still come solely from the pure
     `GatewayUsagePolicyEvaluator`, which it merely calls and whose answer it
     projects onto a flat `GatewayUsagePolicyDecisionOutcome` with five
     distinguishable states: `NO_POLICY`, `ALLOW`, `LIMIT_EXCEEDED`,
     `USAGE_UNKNOWN`, `INACTIVE`. **`NO_POLICY` is distinct from `ALLOW`**:
     an actor with no enabled policy is not an actor whose limits were
     checked and found satisfied, and no usage is even read when no policy
     exists. Ambiguous configuration remains an explicit error —
     `GatewayUsagePolicyAmbiguousException` propagates unchanged and no
     policy is picked. The caller's exact instant is forwarded unchanged (the
     service never calls `Instant.now()`), keeping the answer deterministic
     and testable, and the same trimmed actor is used for both lookups. The
     result carries a state plus already-computed violation metadata only: no
     actor subject, no policy owner/id/label, no raw usage rows, no provider
     content, no request or response content, no secrets, and no PII. The
     service depends only on the resolver and the snapshot provider (the
     evaluator is a static call) and holds no reference to the completion
     service, rate limiter, Redis, providers, repositories, controllers, or
     audit services. **It is not connected to live gateway traffic**: it is
     not a Spring bean and nothing in the gateway path calls it, so it has
     zero effect on real requests and rate limiting is still governed solely
     by `GatewayRateLimiter` configuration. **Concurrent enforcement and
     reservation are intentionally not implemented** — no Redis counters, no
     request or token reservations, no atomic check-and-consume, no locks,
     and no transactions around gateway completion — since two independent
     reads are not an atomic check; that belongs to the later
     runtime-enforcement milestone.
     An atomic enforcement primitive for policy **request** limits now
     exists (`GatewayUsagePolicyCounter`): `tryConsume(actor, window, limit)`
     atomically decides whether consuming one request stays within the limit
     and reports whether the caller may proceed. It exists because the
     persisted-usage snapshot is after the fact — two simultaneous requests
     could read the same historical count and both pass — so the count and
     the decision happen as one indivisible operation, and the count a
     request is admitted against already includes requests admitted before
     it, including in-flight ones. Adoption is all-or-nothing and rejected
     attempts do not increment. It is the primitive only: it knows an actor,
     a window, and a number, and has no notion of `GatewayUsagePolicy` or of
     policy interpretation. It supports the two request limits —
     `MINUTE` (current UTC minute) and `DAY` (current UTC calendar day),
     fixed UTC windows, never the JVM default zone — with the comparison
     `currentCount + 1 <= limit`, so exactly n requests pass under a limit of
     n. Two implementations: the default process-local, thread-safe
     `InMemoryGatewayUsagePolicyCounter` (one atomic map compute per attempt,
     local only, not shared between instances) and
     `RedisGatewayUsagePolicyCounter`, **intended for multi-instance
     enforcement** via one atomic Lua execution per consume (INCR, TTL, and
     the limit compare in a single script — never separate GET/INCR/EXPIRE),
     with counters at
     `aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>`
     namespaced away from the rate limiter's keys and expiring on their own
     at the end of the window; selected by `aegivault.gateway.policy-counter`
     (`IN_MEMORY` default, `REDIS` optional), a switch separate from and
     independent of `aegivault.gateway.rate-limiter`. Redis failures fail
     closed as `GatewayUsagePolicyCounterUnavailableException` with a fixed
     safe message, leaking no host, key, counter, or subject; no HTTP status
     is chosen yet. **The counter is not wired into gateway traffic** — no
     gateway path calls it, so it has zero effect on real requests and
     policies remain unenforced. **`tokensPerDay` remains unenforced by
     design**: token usage is only known after a provider response while
     admission happens before provider invocation, so no truthful token
     number exists to compare; no estimate, max-token assumption,
     character-to-token conversion, or response-size heuristic was invented,
     and token enforcement needs an explicit reservation/accounting design
     in a later milestone.
     A runtime request-policy enforcement service now exists
     (`GatewayUsagePolicyEnforcementService`), applying a resolved policy's
     request limits through the atomic `GatewayUsagePolicyCounter`: for each
     configured limit it calls `tryConsume(actor, MINUTE|DAY, limit)`, and
     that atomic call *is* the admission decision — it never reads a count
     and compares in Java first. `GatewayUsagePolicyEvaluator` is
     deliberately not used for admission, because it evaluates a persisted,
     after-the-fact snapshot in which two simultaneous requests would both
     read the same count and both pass; it remains the tool for
     observational, post-hoc policy decisions. All configured request limits
     must admit, evaluated in a fixed deterministic order (day, then minute),
     a null limit is unconstrained and skipped, and the first rejection
     short-circuits the remaining limits. Its immutable
     `GatewayUsagePolicyEnforcementResult` has four states — `NO_POLICY`,
     `ALLOW`, `REJECTED`, `INACTIVE` — where a `REJECTED` always names the
     rejecting window (`MINUTE` or `DAY`), `NO_POLICY` stays distinct from
     `ALLOW`, and no capacity is consumed in the no-policy, inactive, or
     ambiguous cases (spending an allowance no limit granted would silently
     shrink the actor's real allowance). Ambiguous configuration still
     propagates `GatewayUsagePolicyAmbiguousException` unchanged. A counter
     failure is **not** a rejection: it propagates
     `GatewayUsagePolicyEnforcementException` with a fixed safe message and
     produces no result at all, so "could not check" is never mistaken for
     "limit exceeded", and it fails closed. **Multi-window consumption is now
     atomic**, which removes the earlier partial-consumption limitation: the
     counter contract now takes a typed
     `GatewayUsagePolicyCounterRequest` (actor, instant, per-window limits) and
     returns a `GatewayUsagePolicyCounterResult` (`ALLOWED`, or `REJECTED`
     naming the window), deciding and consuming every requested window as one
     indivisible operation — if all windows have room all are incremented, if
     any is exhausted none is — so a refused request costs the actor nothing in
     any window. `IN_MEMORY` uses a single per-actor atomic state boundary (one
     `ConcurrentHashMap.compute` covers rollover, every capacity check, and
     every increment; never two independent computes per request); `REDIS` uses
     one Lua invocation for the whole attempt, whose read-only check loop
     returns on the first exhausted window before writing anything and whose
     second loop increments every key only when all had room, applying TTLs
     only to newly created keys. The script returns `n` for an admission and
     `-i` for a rejection, so a rejection at the last position can never read
     as an admission, and the rejected window is reported in the fixed
     `evaluationRank()` order (day, then minute) rather than by limit value,
     map order, or timestamp. The single-window operation is preserved and
     delegates to the same path, its `currentCount + 1 <= limit` boundary
     unchanged, and the key format
     `aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>`
     is unchanged. The enforcement service now builds one atomic request from
     the resolved policy's configured request limits and calls the counter
     once, mapping the single result onto its existing states. `tokensPerDay`
     **`tokensPerDay` remains unenforced**: it is never read, and there is no
     token reservation, pre-request token check, response token rollback, or
     character heuristic. **Actor-scoped request limits are now enforced on
     live gateway completions** (`POST /api/gateway/complete`): the global
     `GatewayRateLimiter` runs **first**, then persistent policy
     request-limit enforcement, and only then request inspection, inspection
     audit, provider selection, provider invocation, response inspection, and
     usage recording. A global rejection never reaches the policy check and
     spends no policy capacity; a policy rejection reaches nothing downstream.
     The two controls are independent — never merged, never compensating for
     one another — and keep distinct messages: a global rejection stays HTTP
     429 `Gateway rate limit exceeded.`, while a policy rejection is HTTP 429
     `Gateway usage policy limit exceeded.` with no rejected window, actor,
     policy id, counter, limit, or Redis detail. `NO_POLICY`, `INACTIVE`, and
     `ALLOW` continue through the existing flow unchanged. A policy-counter
     outage is fail-closed and distinct from a rejection: HTTP 500
     `Unable to enforce gateway usage policy.`, never a 429, because an outage
     is not an exceeded limit. Ambiguous policy configuration is HTTP 500
     `Unable to resolve gateway usage policy.`, choosing no policy and
     exposing no policy id, owner, or candidate count.
     **Policy enforcement decisions are now auditable, under their own event
     types**: `GATEWAY_USAGE_POLICY_ALLOWED` / `GATEWAY_USAGE_POLICY_REJECTED`
     under resource type `GATEWAY_USAGE_POLICY`, with the resolved policy UUID
     as the resource id and fixed-field-order metadata only
     (`{"decision":"ALLOW","enforcedWindows":[...]}` or
     `{"decision":"REJECTED","rejectedWindow":"MINUTE"}`). They are deliberately
     separate from `AI_GATEWAY_INSPECTION_ALLOWED` /
     `AI_GATEWAY_INSPECTION_BLOCKED`: a quota decision is not a security
     verdict, so one request can legitimately hold both a policy event and an
     inspection event for two different decisions. The metadata never contains
     the actor (the ledger stores it as its own column), the policy owner or
     label, a configured limit, a usage count, a counter value, a Redis key, or
     any content, PII, or secret. An admitted request records its policy event
     **before** inspection and then its ordinary inspection event; a
     policy-rejected request records only its policy event, because it is never
     inspected, writes no `AI_GATEWAY_INSPECTION_ALLOWED` or
     `AI_GATEWAY_INSPECTION_BLOCKED` entry, selects no provider, invokes none,
     and records no usage row. `NO_POLICY` records nothing (no policy was
     consulted, so there is no decision to evidence), and `INACTIVE` records
     nothing as a deliberate choice — a disabled policy is not applied, so an
     `ALLOWED` event would claim a quota check that never ran. A policy-audit
     failure is fail-closed as HTTP 500
     `Unable to record gateway policy audit event.`, and for a refused request
     it replaces the 429, because a rejection whose evidence was not stored must
     not be reported as though it had been. This is **request-limit
     enforcement for request limits only**: a policy declaring only
     `tokensPerDay` consumes no request capacity (and is recorded with an empty
     `enforcedWindows`), and its token half is enforced separately, by
     reservation and settlement. The completion service depends only on the
     enforcement
     service, never on the policy repository, the counter, a counter
     implementation, Redis, or the evaluator. Existing global rate limiting and
     existing inspection audit behavior are otherwise unchanged.
     **Gateway usage policy lifecycle mutations are now auditable, under
     their own event types**: `GATEWAY_USAGE_POLICY_CREATED` /
     `GATEWAY_USAGE_POLICY_UPDATED` / `GATEWAY_USAGE_POLICY_DELETED` for changes
     to the policy row, deliberately separate from the runtime enforcement
     events `GATEWAY_USAGE_POLICY_ALLOWED` / `GATEWAY_USAGE_POLICY_REJECTED`
     (a quota decision about one request) — a definition change is not an
     enforcement decision and never reuses its name. All of them share resource
     type `GATEWAY_USAGE_POLICY` and the policy UUID as the resource id, and
     the actor is the authenticated JWT subject, stored in the ledger's own
     column and never repeated into the event data. The metadata is
     **intentionally minimal** and deterministic — one closed-vocabulary
     field, `{"action":"CREATED"}` / `{"action":"UPDATED"}` /
     `{"action":"DELETED"}` — because the ledger must prove that the policy
     changed, not copy it: no policy name, description, request/token limits,
     enabled state, `ownerSubject`, raw request body, Redis key, counter,
     usage data, prompt/response content, PII, or secret. Each event is
     appended only **after** the corresponding mutation is durable and only on
     success, so a validation failure or a foreign/missing policy appends
     nothing. An append failure is **fail-closed** as HTTP 500
     `Unable to record gateway policy audit event.` with no SQL detail, hash,
     actor, policy id, or exception text — and it is fail-closed **without
     rolling the mutation back**: no compensation transaction exists, a deleted
     policy is never recreated to make its event succeed, and the honest
     consequence is a real failure window in which a committed mutation exists
     with no ledger entry. `AI_GATEWAY_INSPECTION_ALLOWED` /
     `AI_GATEWAY_INSPECTION_BLOCKED` and the runtime policy-enforcement events
     are unchanged. **Policy audit history is now queryable by the
     authenticated owner** via `GET /api/gateway/policies/{policyId}/audit`:
     the caller's own history for one policy, `{"entries":[{eventType,
     resourceId, eventData, createdAt}]}`, newest first and **bounded to 100
     entries** (the bound is applied in the database query, never by trimming
     in Java; there is no pagination). The read is owner-scoped by JWT subject
     in the same query as the resource id — never by resource id alone — so
     there is no cross-user reporting and no ADMIN bypass. **History works
     even after the policy is deleted**, because the query reads the ledger
     and never the policy table: the ledger is the historical record. A
     foreign or unknown policy id is an empty `200` rather than a `404`, so
     the endpoint cannot be used to probe for someone else's policy. Only
     policy-specific events are returned — the three lifecycle types and the
     two runtime enforcement types — and inspection and unrelated events are
     excluded by the query itself. `eventData` comes back verbatim, never
     enriched with a policy name, description, limit, enabled state, owner,
     usage, counter, Redis key, prompt, provider response, PII, or secret,
     and historical policy state is never reconstructed from current CRUD
     data; no chain hash, sequence number, actor subject, or internal id is
     exposed. The query is strictly read-only, the ledger stays append-only,
     and **cryptographic verification remains a separate concern** —
     `GET /api/audit/verify` is unchanged. No token-budget or enforcement
     behavior is added: it reads usage and changes no enforcement.



   with a deterministic zero-configuration `MockLlmProvider` for local/test
    use only — labelled mock completions, no network, no credentials. A
    local Ollama provider implementation also exists (`OllamaLlmProvider`
    behind the same interface, generate API over plain HTTP via the
    existing Spring HTTP stack with localhost-only base URL plus
    connection/read timeout configuration, generic safe failures).
    Provider selection is now configuration-driven
    (`aegivault.gateway.provider`, `MOCK` default, `OLLAMA` supported;
    never inferred from the model name and never chosen by API callers):
    exactly one `LlmProvider` bean is active at a time and the default
    deployment still uses the mock, so Ollama support exists in the
    application without the deployment being configured to use it.
    No external cloud provider exists.
    `POST /api/gateway/complete` inspects under the fixed strict policy and
    forwards only ALLOW requests to the `LlmProvider` resolved through the
    small `LlmProviderSelector` abstraction (validates the model and returns
    the single configuration-wired provider); BLOCK never reaches the provider and returns the safe decision as
    200 data, with the same single metadata-only audit entry per inspected
    request. Each successful provider response is inspected separately
    before it reaches the client (same detectors, same strict policy,
    distinct response-inspection result): clean responses return unchanged
    as ALLOW, sensitive responses are blocked as 200 BLOCK data with no
    provider payload, and provider content is never persisted or logged.
    No external cloud provider integration exists. No response redaction or
    rewriting exists.
    A live Ollama gateway smoke test exists but is opt-in only
    (`OllamaGatewaySmokeTest`, enabled by `AEGIVAULT_OLLAMA_TEST=true` or
    `-Daegivault.ollama.test=true`; skipped by default with no Ollama
    connection, so the normal suite never requires Ollama; when enabled a
    local Ollama server with an installed model is required — documented
    default `llama3.2`, override with `AEGIVAULT_OLLAMA_MODEL` /
    `-Daegivault.ollama.model`, base URL with `AEGIVAULT_OLLAMA_BASE_URL` /
    `-Daegivault.ollama.base-url`).
    Gateway completions are now rate-limited per authenticated actor (20
    requests per actor per 1-minute fixed window) before inspection,
    audit, provider selection, or provider invocation: rejected requests
    return HTTP 429 with the safe message only and append no inspection
    audit entry, while security BLOCK behavior (HTTP 200 data) is
    unchanged. `GatewayRateLimiter` is now an abstraction with two
    implementations behind the same policy: the default process-local
    in-memory limiter (`IN_MEMORY`, no Redis) and an optional Redis
    limiter (`REDIS`, per-actor counters at
    `aegivault:gateway:rate-limit:<actorSubject>` via one atomic Lua
    execution per attempt, intended for multi-instance enforcement;
    selected by `aegivault.gateway.rate-limiter`, default `IN_MEMORY`).
    Redis outages fail closed as a generic 500. Rate limiting still runs
    before inspection/audit/provider flow.
* Redis is used only for the optional gateway rate limiter; no other
  Redis implementation exists.
* No frontend exists yet.
* Atomic daily **token-budget reservation** infrastructure
  (`GatewayTokenBudget`, `gateway.policy.budget` package), as a primitive
  only. It is separate from request-rate enforcement: it holds tokens, while
  `GatewayUsagePolicyCounter` counts requests and `GatewayRateLimiter` limits
  request rate, and it shares no state, key, or window arithmetic with either.
  The single operation
  `tryReserve(actorSubject, windowStart, limit, requestedTokens)` atomically
  decides and performs one reservation for one actor on one UTC day, succeeding
  exactly when `usedTokens + reservedTokens + requestedTokens <= limit` and
  adding the full amount or nothing at all; a rejected attempt consumes no
  capacity, so hammering a spent budget cannot push the day's total further
  over. The result is an immutable `GatewayTokenBudgetReservation` with only
  `RESERVED` / `REJECTED`, a `reservationId`, and the `reservedTokens` it
  holds — the minimum contract a later reconciliation needs — and exposes no
  current total, remaining tokens, limit, key, or actor, so a rejection cannot
  reveal how much budget is left. Both implementations are concurrency-safe and
  actor/day scoped: `InMemoryGatewayTokenBudget` uses one atomic
  `ConcurrentHashMap.compute` per attempt (never two), and
  `RedisGatewayTokenBudget` uses one Lua execution per reservation (read,
  compare, and write server-side — never GET/compare/INCR as separate
  application operations), with budgets at the new namespace
  `aegivault:gateway:token-budget:<windowStart>:<actorSubject>`, distinct from
  the unchanged policy-counter and rate-limit namespaces, and a TTL covering
  the rest of the UTC day plus a clock-skew grace. Redis failures, empty
  results, and unrecognised results all fail closed as
  `GatewayTokenBudgetUnavailableException` with a fixed safe message; invalid
  input is rejected before any state is touched. **The requested amount is
  supplied explicitly by a future caller and no token estimation exists
  anywhere** — no character conversion, response-size guess, max-token
  assumption, or model formula. Reservations can now also be **reconciled
  against actual provider usage** via
  `reconcile(actorSubject, windowStart, reservationId, actualTokens)`, which
  removes the reservation and replaces the held amount with the real one, so
  the day moves to `existingAccountedTotal - reservedTokens + actualTokens`:
  `reserved 100 / actual 80` leaves 80 accounted and `reserved 100 / actual 20`
  releases 80. **Actual usage is never clamped** — `reserved 100 / actual 140`
  succeeds and records 140, because provider usage is a post-provider fact and
  recording 100 would understate the cost; if that takes the day past its limit,
  the day is left overspent and every later reservation is rejected by the same
  capacity boundary. `actualTokens == 0` is accepted, since a provider can
  legitimately report no tokens, while a negative amount is refused. Reservations
  are **single-use**: a successful reconciliation consumes the id, so a repeat
  or retried callback fails exactly like an unknown id, and an unknown,
  already-settled, or cross-actor/cross-day id is one indistinguishable
  `GatewayTokenBudgetReservationStateException` — deliberately distinct from the
  infrastructure-level `GatewayTokenBudgetUnavailableException`, so an outage
  can never read as ordinary traffic and no actor can settle another's
  reservation. Its result is the minimal `GatewayTokenBudgetReconciliation`,
  whose whole surface is the single `RECONCILED` state: no total, no remaining
  capacity, no limit, no actor, no day, no key, and no internal reservation
  state. Reconciliation is as atomic as reservation — the in-memory budget
  performs the lookup, removal, and adjustment inside the *same* single
  per-actor-and-day `ConcurrentHashMap.compute` (never a second compute), and
  the Redis budget does it all in **one Lua execution** that returns before any
  write when the reservation is absent and otherwise removes the field and
  adjusts the total without touching the TTL (never GET/compare/HDEL/INCR as
  separate application operations). **Nothing is wired into the gateway path and
  `tokensPerDay` is still not enforced**: no completion service, enforcement
  service, provider path, or audit code references this package, nothing
  reserves before a provider call or settles after one, no request is ever
  rejected on token grounds, and release/refund is still not implemented.
  The **input to a future reservation** is now defined as a standalone
  contract, `GatewayTokenBudgetReservationRequest`: a one-field immutable
  record carrying the **requested reservation amount** — the number of tokens
  a caller explicitly asks the budget to hold, and nothing else, with no
  second input such as a prompt length, model name, or output cap. The amount
  must be present and strictly positive; zero and negative figures are
  refused as contradictions rather than small reservations, and an absent
  amount is rejected distinctly from a zero one. It is **not** actual
  provider usage (still obtained only after a provider response, and already
  accounted for by `reconcile`), and it is **not** an estimate, an inferred
  token count, a response size, a character count, or a byte count. **The
  system intentionally does not estimate or infer this amount**: there is no
  tokenizer, no character- or byte-to-token conversion, no model-specific
  formula, no pricing table, no max-token assumption, and no heuristic or
  fallback — a fabricated count is a number the system cannot stand behind,
  and a silently wrong one would make the budget itself untrustworthy. The
  type is not wired to anything: it is not a parameter of
  `GatewayTokenBudget`, no implementation accepts it, and no other production
  type in the package references it, so it is not yet a call path. No
  decision or enforcement state was added with it — no `ALLOW`/`REJECTED`
  budget response, no HTTP status, and no token policy outcome — since
  choosing what a request reserves and deciding what a rejection means belong
  to the enforcement layer. `tokensPerDay` remains unenforced.
  A **`reservationTokensPerRequest`** field was added to
  `GatewayUsagePolicy` (column `reservation_tokens_per_request`, migration
  V11, nullable, no default, nothing backfilled). It is the maximum tokens
  reserved for one request before provider invocation, and it is a
  **policy-controlled configuration value, not a client input** — without it
  a future `tokensPerDay` enforcement would have to trust a caller-supplied
  amount, and a caller could always declare 1 token and never be refused.
  Cross-field validation, enforced by both the aggregate and PostgreSQL
  CHECKs: a present amount must be strictly positive, must never exceed
  `tokensPerDay`, and **is required whenever `tokensPerDay` is present**, so a
  daily token policy cannot exist without a deterministic pre-request amount.
  No `tokensPerDay` means the amount may be null (and may also be declared);
  values are never silently clamped. Existing policies with a null
  `tokensPerDay` keep working unchanged, and the "requires reservation"
  constraint is added `NOT VALID` so pre-existing token rows are grandfathered
  rather than fabricated or blocked. The field is accepted and returned on
  the existing create/update/read endpoints, with no new endpoint, no change
  to owner scoping, resolver, or evaluator, and no addition to lifecycle
  audit event data. **No token estimation or prediction was added**: the
  amount is configuration, not a computed figure, and actual provider token
  usage is still obtained only after the provider response and reconciled
  against the reservation then. `tokensPerDay` is now enforced on live gateway
  completions.
  A **token-budget enforcement coordinator** is live as
  `GatewayTokenBudgetEnforcementService.reserve(actorSubject, now)`, called by
  `GatewayCompletionService` after request inspection and provider selection and
  immediately before provider invocation. It resolves the actor's effective policy and, when the
  policy declares `tokensPerDay`, makes **one atomic `tryReserve` call** using
  exactly the policy-controlled `reservationTokensPerRequest` — the configured
  amount passed through unchanged, with **no token estimation performed**: no
  character or prompt-length conversion, no response-size guess, no model name,
  and no max-token heuristic. The reservation amount therefore comes from
  trusted configuration rather than per-request client input. The window is the
  **current fixed UTC calendar day**, derived from the caller-supplied instant
  with the same arithmetic the request counter uses, never a rolling 24 hours
  and never the JVM default zone. Its immutable
  `GatewayTokenBudgetEnforcementResult` has five explicit states: `NO_POLICY`,
  `INACTIVE`, `NO_TOKEN_POLICY`, `RESERVED`, and `REJECTED` with the closed
  reason `TOKEN_BUDGET_EXCEEDED` — "no token rule applies" is deliberately kept
  distinct from "the token rule refused you". Only `RESERVED` carries the
  `reservationId` and `reservedTokens` a later reconciliation step will need;
  no actor subject, limit, remaining budget, current usage, Redis key, or
  storage detail is exposed. The three non-reserving states never contact
  `GatewayTokenBudget` at all, so no capacity is spent where no limit granted
  it, and an ambiguous policy propagates
  `GatewayUsagePolicyAmbiguousException` unchanged. **Failure is not
  rejection**: `GatewayTokenBudgetUnavailableException` becomes
  `GatewayTokenBudgetEnforcementException`, deliberately a distinct type from
  the request-limit `GatewayUsagePolicyEnforcementException` so a caller can
  tell which control could not be evaluated, and it never becomes a
  `REJECTED`. Dependencies are limited to `GatewayUsagePolicyResolver` and
  `GatewayTokenBudget` — no completion service, counter, rate limiter,
  provider, repository, controller, or audit ledger. **Reconciliation is not
  performed here**; the reservation id is simply handed on for the later
  runtime step that follows the provider response. `tokensPerDay` is still NOT
  enforced and live gateway integration remains pending.
  A **post-reservation settlement coordinator** now exists as
  `GatewayTokenBudgetSettlementService.settle(actorSubject, reservedAt,
  reservationId, settlement)`, with no live caller and no Spring annotation. It
  maps three accounting truths onto three states: **known provider usage is
  reconciled exactly** (`RECONCILED`) using the provider's own total, never
  capped back to the reservation; a **provider invocation failure releases the
  reservation at zero** (`RELEASED`), since no response means nothing was
  generated or consumed; and a **response with an unknown token count leaves the
  reservation held** (`UNKNOWN_USAGE`) until its own UTC day key expires. That
  conservative choice is a genuine trade-off — the actor may temporarily have
  less capacity available than they otherwise would, but the system never
  books spend that may not have happened and never books a fabricated figure.
  Unknown usage is deliberately not treated as zero (a response may have cost
  tokens, and releasing would under-account) and is never estimated (no honest
  basis for a number exists); no separate hold-forever mechanism is added, the
  hold simply rides out the TTL it already had. A **response blocked by
  security inspection is settled exactly like any other response** — known
  usage reconciles, unknown usage stays held, and the existing security BLOCK
  result is unchanged — so a provider *failure* and a provider-response *block*
  are never conflated; the only difference is whether a response exists. The
  window is the same fixed UTC calendar day, derived from the caller's own
  instant and never from `Instant.now()`. A failed reconciliation raises
  `GatewayTokenBudgetSettlementException` with a fixed generic message rather
  than reporting a settlement that did not happen, and is never retried, since
  a failed reconciliation leaves server state unknown; a
  `GatewayTokenBudgetReservationStateException` propagates unchanged instead of
  being swallowed as unknown usage. The service reserves nothing, reads no
  provider content, and adds **no token estimation** of any kind; it depends
  only on `GatewayTokenBudget`. It is called by `GatewayCompletionService` once
  per provider phase, after provider invocation and before the response is
  size-checked, inspected, recorded, or returned.
  **`tokensPerDay` is now enforced on `POST /api/gateway/complete`.**
  `GatewayCompletionService` gained exactly two dependencies —
  `GatewayTokenBudgetEnforcementService` and
  `GatewayTokenBudgetSettlementService` — and nothing below them: never the
  `GatewayTokenBudget` primitive, a budget implementation, Redis, the policy
  repository, or the policy resolver. One `GatewayTokenBudget` bean is chosen at
  startup from `aegivault.gateway.token-budget` (`IN_MEMORY` by default,
  `REDIS` for multi-instance), and both coordinators are built over it. The
  fixed order is: global rate limiter, request policy enforcement, request
  security inspection, inspection audit, provider selection, **token reservation**,
  provider invocation, **token settlement**, provider-response inspection, usage
  recording. Reservation happens **after** request inspection and provider
  selection and **before** provider invocation, so a request blocked by
  inspection — or one whose provider could not be selected — never reserves
  anything. `NO_POLICY`, `INACTIVE`, and `NO_TOKEN_POLICY` continue normally with
  nothing reserved and the budget never consulted. `REJECTED` is HTTP **429**
  `{"message": "Gateway token budget exceeded."}` before the provider runs, so
  no provider invocation, no usage row, no provider-response audit event, no
  second inspection, and no exposure of usage, remaining budget, limit, reserved
  amount, or reservation id. A budget that cannot be consulted is **not** a
  rejection: it is HTTP **500** `{"message": "Unable to enforce gateway token
  budget."}` and never becomes a 429. Settlement runs as soon as a provider
  response exists: a **known** `totalTokens` is reconciled **exactly** as
  reported (never capped to the reservation), **unknown** usage leaves the
  reservation held until its own UTC day TTL expires (never released to zero,
  never estimated), a **provider failure** settles `NO_RESPONSE` and reconciles
  at **zero**, and a security BLOCK or an oversized response is settled exactly
  like any other response because the provider really did produce one. A
  **settlement failure** is HTTP **500** `{"message": "Unable to settle gateway
  token budget."}` and **fails closed**: no provider content, no security BLOCK
  body, and no normal completion, with no automatic retry. Token budgets here
  are **reservation and accounting, not token prediction**: the reserved amount
  is always the policy's own `reservationTokensPerRequest` and real usage is only
  ever what a provider reported — **no token estimation, tokenizer, pricing, or
  prediction was added anywhere**. A token-only policy (no `requestsPerMinute`,
  no `requestsPerDay`) is therefore genuinely active: it reserves, calls the
  provider, reconciles usage, records the usage row, and succeeds. Usage
  recording, the usage schema, and the audit ledger are unchanged: still no
  token-budget audit events, and no reservation id is stored on a usage row.

## Next planned step

Continue wiring the authenticated dataset flow. Dataset input storage exists as
PostgreSQL BYTEA behind `DatasetInputSource`, the upload path
(`POST /api/datasets/{id}/input`) and the run REST endpoints (`POST /api/runs`,
`GET /api/runs`, `GET /api/runs/{runId}`) and the artifact download
(`GET /api/runs/{runId}/artifact`) are implemented, and sanitized output is
stored per run behind `SanitizationArtifactStore`. Owner-scoped reusable
sanitization policies persist as `SanitizationPolicy` aggregates with
normalized `sanitization_policy_rules` rows, created and read through
`POST /api/policies`, `GET /api/policies`, and
  `GET /api/policies/{policyId}`; run creation resolves the referenced
  policy owner-scoped and freezes its name/version/rules into the run's
  immutable snapshot. Dataset profiles persist through
  `DatasetProfileService` and read back through
  `GET /api/datasets/{datasetId}/profile`, and computed explicitly through
  `POST /api/datasets/{datasetId}/profile`. Still not implemented:
  automatic profiling during upload or run creation, and background
  processing. The audit ledger
remains a later milestone. The domain CSV sanitization pipeline and the run
persistence/lifecycle foundation are implemented and tested.

## Future phases

* Phase 1 — Foundation (persistence layer, user/auth groundwork)
* Phase 2 — PII Detection (pattern-based detectors, findings model)
* Phase 3 — CSV Sanitization MVP (upload → detect → sanitize → download)
* Phase 4 — PostgreSQL Sanitization (source table connectors, sanitized copies)
* Phase 5 — Large Dataset Processing (chunked/streaming execution)
* Phase 6 — Policy Engine (stored, versioned sanitization policies)
* Phase 7 — Cryptographic Audit Ledger (hash-chained, tamper-evident records)
* Phase 8 — AI Security Gateway (outbound payload inspection and enforcement)
* Phase 9 — Redis Controls (rate limiting, processing locks, policy caching)
* Phase 10 — Production Hardening (security configuration, error handling,
  operational readiness)
* Phase 11 — React Dashboard (JavaScript frontend for the above)
* Phase 12 — Final Documentation / Benchmarks / Demo (measured numbers only)
