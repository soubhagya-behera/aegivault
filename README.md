# Aegivault

Spring Boot 4.1.1 (Java 17, Maven) backend in [`backend/`](backend/), persisting
to PostgreSQL via Spring Data JPA with Flyway owning schema evolution.

Aegivault is being built as a data sanitization and AI security platform; only
the foundation exists so far. Implemented and verified today (360 passing tests:
context/persistence/API suites against a real local PostgreSQL plus pure unit
suites):

* Stateless JWT authentication (register/login) with BCrypt password hashing and
  USER/ADMIN roles seeded by Flyway.
* Owner-scoped datasets: authenticated create/list/get endpoints where the owner
  always comes from the verified JWT subject; no cross-user access.
* Eleven conservative whole-value PII detectors (email, phone, credit card, IP
  address, UUID, API key, password-labelled values, JWT structure, person-name
  heuristic, address heuristic, labelled custom identifiers) behind
  `PiiDetectorRegistry`.
* Schema-aware PII profiling over a deterministic bounded sample, returning
  metadata-only `ColumnProfile`/`DatasetProfile` results.
* Strict bounded CSV schema discovery and CSV profiling: header validation with
  duplicate-header rejection, exact row-width validation, quoted/escaped/embedded
  fields, explicit safety limits, and CSV → `ColumnInput` → `DatasetProfiler`
  integration.

Not implemented yet — documented as planned work and limitations, not claimed as
working: PII sanitization or transformation of any kind, masking/tokenization,
profile persistence, CSV file-upload REST API, scanning of stored database data,
policy engine, cryptographic audit ledger, AI security gateway, Redis-based
controls, Spring Batch pipelines, and the React dashboard. No compliance
certification is claimed.

## Local setup

1. Install Java 17 and run PostgreSQL locally.
2. Copy `backend/src/main/resources/application-example.properties` to
   `backend/src/main/resources/application.properties` (same directory) and
   fill in your real local PostgreSQL credentials.
3. `application.properties` is git-ignored and must never be committed; only
   `application-example.properties` (placeholders, no secrets) is tracked.
4. From `backend/`: `.\mvnw.cmd spring-boot:run`.
