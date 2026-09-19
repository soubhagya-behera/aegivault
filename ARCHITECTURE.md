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

The repository currently contains only the backend foundation:

* Spring Boot 4.1.1 application skeleton (Java 17, Maven).
* PostgreSQL datasource configuration (local `application.properties`,
  git-ignored; committed `application-example.properties` template).
* Spring Data JPA with `ddl-auto=validate` — Hibernate never modifies the
  schema.
* Flyway dependency, enabled, pointing at `db/migration/` (V1 datasets
  migration applied).
* Spring Security, Validation, and Actuator dependencies (no custom security
  configuration yet).
* One context-load test; no domain code.
* V1 Flyway migration: `datasets` table (ingestion aggregate root, UUID key,
  UTC timestamps, owner/status indexes).
* `Dataset` JPA entity mapped 1:1 to the Flyway schema plus a minimal
  repository; persistence proven by a `@DataJpaTest` against PostgreSQL.

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
