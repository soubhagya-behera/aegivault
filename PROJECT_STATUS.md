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
* Pure unit test totals: 446 tests (249 PII/profile + 86 CSV discovery/profiling + 77 sanitization
  + 34 CSV sanitization pipeline), 0 failures, 0 errors, 0 skipped.
* Repository total: 471 tests, 0 failures, 0 errors, 0 skipped — 446 pure unit
  tests plus 25 context/persistence/API tests run against the real local
  PostgreSQL.
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
* PII detection and CSV ingestion together still form a foundation only: individual whole-value detectors plus registry orchestration, column/dataset profiling, CSV discovery on caller-supplied input, the domain sanitization engine described below, and the end-to-end CSV sanitization pipeline described below. Not completed yet:
  PostgreSQL schema discovery, dataset-wide PII scanning over stored data,
  persistence of profiles, findings, plans, or sanitization runs, confidence scoring,
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
* No sanitization persistence or REST API exists yet. The CSV rewrite pipeline now exists as a
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
* No audit ledger exists.
* No AI gateway exists.
* No Redis implementation exists.
* No frontend exists yet.

## Next planned step

Wire the proven CSV discovery/profiling boundary and the sanitization engine
into the authenticated dataset flow (an upload/ingest path and persistence of
profile metadata), then the audit ledger. Sanitization persistence and the audit ledger remain
later milestones: no CSV upload API, no profile persistence, and no sanitization persistence
is complete yet. The domain CSV sanitization pipeline itself is implemented and unit-tested.

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
