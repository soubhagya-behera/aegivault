# Aegivault Architecture Decision Records

Small, append-only list of decisions actually made. New entries get the next
sequential ADR number; existing entries are updated in place, never rewritten
as new numbers.

## ADR-001 — Modular Monolith

* **Decision:** Build the backend as a modular monolith: one Spring Boot
  deployable with modules separated by explicit package and service
  boundaries.
* **Reason:** A single developer/small team shipping an MVP benefits from one
  codebase, one deployment, and no distributed-system overhead, while module
  boundaries keep future extraction possible.
* **Consequences:** Simpler development, testing, and deployment; discipline
  is required to respect module boundaries since the compiler alone will not
  enforce them; a module can be extracted into a service later if a genuine
  scaling or ownership need appears.
* **Status:** Accepted.

## ADR-002 — PostgreSQL as Primary Durable Database

* **Decision:** PostgreSQL is the system of record for all durable data:
  users, datasets, findings, policies, outputs, and audit entries.
* **Reason:** Relational model fits the domain, PostgreSQL is mature and
  well-supported by Spring Data JPA, and a single durable store avoids
  consistency questions in the MVP.
* **Consequences:** All persistence work targets PostgreSQL first; any
  additional store must justify itself against a requirement PostgreSQL
  cannot meet; local development requires a running PostgreSQL instance.
* **Status:** Accepted.

## ADR-003 — Flyway for Database Schema Management

* **Decision:** All schema evolution goes through versioned Flyway migrations;
  Hibernate is configured with `ddl-auto=validate` and never modifies the
  schema.
* **Reason:** Migrations are reviewable, repeatable across environments, and
  keep the database history explicit;validate-only JPA mapping removes a
  whole class of startup-mutates-production-schema accidents.
* **Consequences:** Every schema change needs a migration file; developers
  run migrations locally on startup/test; no `create`, `create-drop`, or
  `update` modes in any environment.
* **Status:** Accepted.

## ADR-004 — React JavaScript Frontend / No TypeScript

* **Decision:** The dashboard will be built in React using JavaScript, not
  TypeScript.
* **Reason:** Matches the maintainer's working stack and keeps the frontend
  toolchain minimal for the project's scope.
* **Consequences:** No type-checking step in the frontend build; component
  contracts are documented with propTypes or JSDoc where it matters.
* **Status:** Accepted (applies when frontend work begins; no frontend
  exists yet).

## ADR-005 — No Paid AI Dependency for Core Functionality

* **Decision:** Core functionality must work with no paid AI API. Tests use a
  Mock AI provider; local development uses Ollama.
* **Reason:** Keeps the project runnable offline and at zero cost, avoids
  leaking development data to third-party APIs, and forces the deterministic
  detection pipeline to stand on its own.
* **Consequences:** AI-assisted features are designed behind a provider
  interface from the start; paid APIs may only appear as optional,
  explicitly configured providers, never as a requirement.
* **Status:** Accepted.

## ADR-006 — JWT via Spring Security Resource Server (Nimbus), HS256

* **Decision:** Issue and verify bearer tokens with Spring Security's
  `JwtEncoder`/`JwtDecoder` (Nimbus JOSE under the hood) using HMAC
  SHA-256 and a 256-bit secret from `aegivault.security.jwt-secret`.
  Tokens carry `sub` (user UUID), `email`, and `roles` claims with a
  60-minute expiry. No JJWT or other separate JWT library, no
  refresh-token infrastructure in this step.
* **Reason:** The resource-server starter is already part of the Spring
  Security stack, so JWT support arrives without a new third-party
  dependency or hand-rolled signing; short-lived access tokens keep the
  stateless API simple until a demonstrated need for refresh tokens.
* **Consequences:** The JWT secret is required configuration in every
  environment (startup fails fast below 256 bits); adding refresh tokens
  or key rotation later is a deliberate new decision, not an accident.
* **Status:** Accepted.

## ADR-007 — User Entity as the UserDetails Principal

* **Decision:** The `User` JPA entity implements Spring Security's
  `UserDetails` directly (email as username, BCrypt hash as password,
  roles as `ROLE_<name>` authorities). Login authenticates through the
  standard `AuthenticationManager`/DAO provider backed by a
  `UserDetailsService` over the `users` table. Passwords use BCrypt
  strength 12; emails are normalized to lowercase on write and lookup.
* **Reason:** No separate principal class is needed — the entity already
  carries exactly the fields authentication requires, and the standard
  provider gives BCrypt verification plus enabled-account checks without
  custom code.
* **Consequences:** Authentication stays coupled to the `User` mapping
  (acceptable while local passwords are the only credential type);
  introducing OAuth2 or other credential types would revisit this.
* **Status:** Accepted.

## ADR-008 — Bounded Deterministic PII Column Profiling Without Raw-Value Retention

* **Decision:** Profile dataset columns over a deterministic bounded sample
  (default 100 values, first-N order): `PiiColumnProfiler` delegates each
  sampled value to the existing `PiiDetectorRegistry`, aggregates per-type
  detection counts plus observed detection rates (denominator: analyzed
  non-blank values), and returns an immutable `ColumnProfile` carrying only
  counts (supplied/analyzed/analyzable) and rates; `DatasetProfiler` composes
  column profiles into an immutable `DatasetProfile` with deterministic
  column-name ordering. No raw values, raw PII, confidence scores, CSV
  parsing, persistence, or REST exposure in this step.
* **Reason:** Keeps detector logic separate from dataset aggregation, bounds
  cost on wide columns, makes sample-vs-full-dataset semantics explicit, and
  guarantees profiling results can never leak the data they describe.
* **Consequences:** Callers supply column values directly until ingestion
  exists; oversized columns report supplied vs analyzed counts instead of
  claiming full coverage; adding ingestion, persistence, confidence, or
  policy mapping later is a deliberate new decision.
* **Status:** Accepted.

## ADR-009 — Strict Bounded CSV Discovery at the Profiling Boundary

* **Decision:** CSV ingestion is a discovery/extraction boundary, not a
  detection or sanitization boundary. `CsvDiscoveryService` (with a hand-written
  `CsvTokenizer`, no CSV library) reads UTF-8 CSV input under explicit
  `CsvLimits` and produces an immutable `CsvSchema` plus bounded per-column
  samples (`CsvSample`); `CsvDatasetProfiler` converts those samples into
  `ColumnInput` values and delegates to the existing `DatasetProfiler`, so
  detection stays in `PiiDetectorRegistry`/`PiiColumnProfiler`. The rules
  chosen: the first record is always the header; column names are preserved
  verbatim (never trimmed or renamed) while blank names and duplicate names
  (compared after trimming and case folding) are rejected; every data record
  must match the header width exactly, and nothing is dropped, padded, merged,
  or invented; all-blank records (empty lines, whitespace-only lines,
  delimiter-only records such as `,,`) are skipped and counted neither as
  sampled nor as encountered rows; sampling is bounded (default 100 rows per
  column, the same default as `PiiColumnProfiler`) while rows read are counted
  separately from rows retained, and no full-dataset row count is invented;
  raw values are never logged, persisted, transformed, exported, sent to any
  network/AI service, or included in a profile or exception message (errors
  name row numbers, column indexes, and limits only).
* **Reason:** Silent schema mutation or silent row repair would make later
  sanitization unsafe — a sanitizer that trusts a "repaired" schema could
  rename a column the caller relies on, or mask the wrong field — so ambiguity
  fails fast at the boundary instead. Bounded sampling keeps memory and CPU
  proportional to the configured limits rather than to input size, explicit
  read-vs-retained counters prevent a sample from being mistaken for the whole
  dataset, and keeping detection in the existing PII layer avoids a second
  profiler implementation. A small hand-written parser was preferred to a new
  CSV dependency because the required grammar (quoted fields, `""` escapes,
  LF/CRLF/CR terminators) is small, fully testable, and avoids another
  dependency to audit.
* **Consequences:** CSV input that would previously have needed lenient repair
  (duplicate or blank headers, ragged rows, text after a closing quote) is
  rejected with a domain `CsvParseException` instead of being accepted.
  Ingestion remains non-persistent: the accepted input is buffered within the
  byte limit, no raw data is stored, and no profile is stored. Wiring CSV
  profiling into the Dataset flow, persisting profiles, sanitization, and
  chunked processing of inputs larger than the byte limit are deliberate later
  decisions. The configured limits bound one discovery call and are not a
  complete denial-of-service protection.
* **Status:** Accepted.
