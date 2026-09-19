# Aegivault Development Guide

## Working rules

* Inspect the existing code before changing it. Understand what is already
  there — including configuration and tests — before adding anything.
* Do not recreate existing functionality. Extend or refactor it instead.
* Implement incrementally: one small, complete, tested step at a time.
* Do not generate hundreds of files at once. Large bulk changes cannot be
  reviewed properly.
* Do not create empty packages just because a future module is planned.
  Create folders/packages only when actual implementation requires them.
* Keep the modular monolith architecture: one deployable, modules with
  explicit boundaries, cross-module access through service interfaces only.
* Prefer simple production-quality solutions over clever or elaborate ones.
* Do not add technologies merely for resume keywords. Every dependency must
  earn its place against a real requirement.
* No production Java source file should exceed 400 lines. Prefer splitting
  classes by responsibility when approaching the limit, and avoid god
  classes — but do not artificially split a small cohesive class merely to
  satisfy a number. This limit applies to production source code;
  migrations, configuration, and documentation stay cohesive as written.

## Technology constraints

* No Kafka unless a real requirement emerges.
* No RabbitMQ unless a real requirement emerges.
* No unnecessary microservices — the modular monolith is the architecture
  (see `DECISIONS.md`, ADR-001).
* No TypeScript. The React frontend uses JavaScript.
* No paid AI API dependency for core functionality. Use a Mock provider for
  tests and Ollama for local AI development (see `DECISIONS.md`, ADR-005).

## Honesty rules

* Security claims must match the actual implementation. Document what the
  code does, not what the roadmap intends.
* Do not claim compliance certifications (GDPR, HIPAA, or others) without
  appropriate evidence — such claims require audit, not assertion.
* Do not call the audit ledger a blockchain. Use the term
  "tamper-evident cryptographically linked audit ledger".
* Do not claim tamper-proof security; the ledger is tamper-evident
  (modification is detectable, not impossible).
* Do not invent benchmark results. Measure performance first, then document
  the measured numbers with the method used to obtain them.

## Development workflow

```
Implement → Test → Review → Document → Commit
```

1. **Implement** the smallest complete increment.
2. **Test** it (`mvn test` at minimum; the suite boots the real PostgreSQL).
3. **Review** the diff for scope creep, dead code, and claim accuracy.
4. **Document** what changed in the relevant root doc and code comments.
5. **Commit** with a meaningful message per the convention below.

## Local setup

Covered in `README.md`: Java 17, local PostgreSQL, copy
`backend/src/main/resources/application-example.properties` to
`application.properties` and fill in real credentials (that file is
git-ignored and must never be committed), then run from `backend/` with
`.\mvnw.cmd spring-boot:run` or `.\mvnw.cmd test`.

## Git commit convention

Use a type prefix and an imperative, specific subject line:

* `feat: ...` — new functionality
* `fix: ...` — bug fix
* `test: ...` — tests without behavior change
* `docs: ...` — documentation only
* `refactor: ...` — restructuring without behavior change
* `chore: ...` — build, tooling, maintenance

Examples: `feat: add email PII detector`, `fix: reject empty CSV upload`,
`docs: update project status after Flyway baseline`.
