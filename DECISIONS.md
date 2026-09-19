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
