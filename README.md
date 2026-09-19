# Aegivault

Backend foundation stage: Spring Boot 4.1.1 (Java 17, Maven) service in
[`backend/`](backend/), planned to persist to PostgreSQL via Spring Data JPA
with Flyway owning schema evolution.

Nothing is implemented yet — no domain modules, no database migrations, no
authentication. Current work is the local-development and Git configuration
foundation only.

## Local setup

1. Install Java 17 and run PostgreSQL locally.
2. Copy `backend/src/main/resources/application-example.properties` to
   `backend/src/main/resources/application.properties` (same directory) and
   fill in your real local PostgreSQL credentials.
3. `application.properties` is git-ignored and must never be committed; only
   `application-example.properties` (placeholders, no secrets) is tracked.
4. From `backend/`: `.\mvnw.cmd spring-boot:run`.
