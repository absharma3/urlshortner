# Project: High-Concurrency URL Shortener Service

## 1. Project Overview & Target Performance
- **Target Scale:** 10,000+ Queries Per Second (QPS) with < 20ms p99 latency for redirection.
- **Primary Goal:** Production-grade, containerized URL shortener with caching, async click tracking, rate-limiting, and comprehensive tests.

## 2. Tech Stack
- **Language & Runtime:** Java 21 (LTS) with Virtual Threads enabled (`spring.threads.virtual.enabled=true`).
- **Framework:** Spring Boot 3.x (Spring Web, Spring Data JPA, Spring Data Redis, Spring Validation).
- **Build Tool:** Gradle (Gradle Wrapper `./gradlew`).
- **Database:** MySQL 8.x (durable persistence, B-Tree indexes on short codes).
- **Cache & Rate Limiting:** Redis (Lettuce client, connection pooling).
- **Documentation:** Springdoc OpenAPI 3.0 (Swagger UI).
- **Testing:** JUnit 5, Mockito, AssertJ, Testcontainers (MySQL + Redis).
- **Containerization:** Docker & Docker Compose.

## 3. Core Architectural Principles & Coding Guidelines

### Design Principles (SOLID & DRY)
- **Single Responsibility (SRP):** Controllers handle HTTP transport; Services encapsulate business rules; Repositories manage persistence. Keep classes lean and focused.
- **Open/Closed (OCP):** Use interface-driven design (e.g., `UrlShortenerStrategy`, `RateLimiterService`) to allow swapping algorithms or implementations without changing core callers.
- **Dependency Inversion (DIP):** Depend on abstractions (interfaces) rather than concrete implementations. Rely on Spring's Dependency Injection.
- **Don't Repeat Yourself (DRY):** Abstract common functionality (e.g., error handling, cache keys, validation routines) into central utilities or aspect/filter pipelines.
- **Keep It Simple (KISS):** Avoid over-engineering; leverage Spring Boot 3 / Java 21 features natively before adding external dependencies.

### RESTful URI Design Standards
- **Resource-Centric Naming:** Use plural nouns for resource endpoints (e.g., `/api/v1/urls`, not `/api/v1/createUrl`).
- **Standardized Error Handling:** All errors MUST return standard **RFC 7807 (Problem Details for HTTP APIs)** payloads (`org.springframework.http.ProblemDetail`).

### Concurrency & Performance Rules
- Enable Virtual Threads (`spring.threads.virtual.enabled=true`) for non-blocking worker thread allocation.
- **Zero Database Reads** on the hot redirection path when cache hits occur in Redis.
- Use atomic operations (`INCR`) in Redis for click counting; flush to MySQL via scheduled async background tasks (`@Scheduled` / `@Async`).
- Explicitly configure connection pool sizing (HikariCP for MySQL, Lettuce pool for Redis).

## 4. Testing Requirements
- **Unit Tests:** Mockito + JUnit 5. Target coverage > 85%. Test Base62 encoding, URL validation, and service logic in isolation.
- **Integration Tests:** `@SpringBootTest` using **Testcontainers** for actual MySQL and Redis containers. **H2 in-memory DB is strictly forbidden.**
- Organize tests using JUnit 5 `@Nested` classes and table-driven testing pattern where appropriate.

## 5. Build & Execution Commands (Gradle)
- **Build Project:** `./gradlew build -x test`
- **Run Unit Tests:** `./gradlew test --tests "*UnitTest"`
- **Run Integration Tests:** `./gradlew test --tests "*IntegrationTest"`
- **Run All Tests:** `./gradlew check`
- **Local Application Run:** `./gradlew bootRun`
- **Docker Deployment:** `docker compose up --build`