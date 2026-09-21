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
* Schema-aware PII profiling foundation: see Current state below.
* PII test coverage (pure unit tests, no Spring/network/DB): 249 PII/profile
  tests passing, 0 failures, 0 errors, 0 skipped (full repository total
  274 including 25 pre-existing auth/dataset/context tests).
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
* Schema-aware PII profiling foundation (pii.profile, not persisted, no CSV parsing, no REST changes): ColumnInput plus ColumnProfile carry only counts, PiiColumnProfiler aggregates registry detections over a deterministic bounded sample (default 100, first-N order; supplied vs analyzed counts recorded), and DatasetProfiler returns an immutable DatasetProfile with deterministic column ordering; detection rates use analyzed non-blank values as denominator and are observed rates only.
* PII detection is currently a detector-level foundation: individual
  whole-value detectors plus registry orchestration, with column and dataset profiling on caller-supplied values. Not completed yet:
  schema discovery from files or PostgreSQL, dataset-wide PII scanning over stored data,
  column-level persistence of profiles, confidence scoring, transformation
  planning, masking, synthetic replacement, relationship-preserving
  anonymization, sanitization jobs, CSV processing pipeline, Spring Batch
  processing, user approval workflow, or policy-driven transformations.
* No sanitization implementation exists.
* No audit ledger exists.
* No AI gateway exists.
* No Redis implementation exists.
* No frontend exists yet.

## Next planned step

Connect CSV ingestion on top of the owned datasets and the new profiling foundation; sanitization, masking, and the audit ledger remain later milestones. No CSV ingestion or sanitization is complete yet.

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
