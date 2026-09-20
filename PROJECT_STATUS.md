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
* Six conservative whole-value PII detectors: `EmailDetector` (practical
  email subset, whole value only), `PhoneDetector` (scoped to supported
  Indian/international-style mobile formats), `CreditCardDetector`
  (normalized 13–19 digits with Luhn checksum), `IpAddressDetector`
  (strict IPv4 dotted-decimal plus IPv6 literal forms, no DNS/network
  calls), `UuidDetector` (strict 8-4-4-4-12 hexadecimal textual form),
  and `ApiKeyDetector` (only explicit `sk-`/`sk-proj-`, `ghp_`/`gho_`/
  `ghu_`/`ghs_`/`ghr_`, and labelled `api_key`/`apikey`/`api-key`
  patterns; bare alphanumeric strings are never classified as keys).
  Detection results never expose raw sensitive values.
* PII test coverage (pure unit tests, no Spring/network/DB): 172 total
  tests passing, 0 failures, 0 errors, 0 skipped.
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
* PII detection is currently a detector-level foundation: individual
  whole-value detectors plus registry orchestration. Not completed yet:
  schema discovery, column profiling, dataset-wide PII scanning,
  column-level PII aggregation, confidence scoring, transformation
  planning, masking, synthetic replacement, relationship-preserving
  anonymization, sanitization jobs, CSV processing pipeline, Spring Batch
  processing, user approval workflow, or policy-driven transformations.
* No sanitization implementation exists.
* No audit ledger exists.
* No AI gateway exists.
* No Redis implementation exists.
* No frontend exists yet.

## Next planned step

Transition from individual PII detectors toward schema-aware/dataset-aware
detection and profiling, once the detector layer is sufficiently complete
and stable. CSV ingestion/processing follows on top of the owned
datasets.

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
