# SETUP — URL Shortener Service

A first-time developer guide to running the URL shortener locally. Two supported topologies:

- **Local app + containerized dependencies** (fastest inner loop, uses your IDE)
- **Full stack in Docker** (production-shaped, one command)

---

## 1. Prerequisites

| Tool | Version | Why |
|---|---|---|
| **Java JDK** | 21 (LTS) | Runtime. If you don't have JDK 21 installed, Gradle will auto-download it to `~/.gradle/jdks/` via the Foojay resolver — no manual install required, first build is ~30s slower. |
| **Docker Desktop** or **Docker Engine + Compose** | Any recent version (Compose v2 syntax) | Runs MySQL 8.0 and Redis 7 locally. Also builds the app container when you use the full-stack path. |
| **Git** | Any recent version | Cloning and version control. |

Verify:
```bash
java -version           # optional — Gradle will provision if missing
docker --version
docker compose version  # v2 syntax; note the space, not a hyphen
git --version
```

The Gradle wrapper is committed — you do **not** need to install Gradle separately.

---

## 2. Repository layout at a glance

```
urlshortner/
├── build.gradle                    # Gradle build + Spring Boot 4.1
├── settings.gradle                 # Foojay JDK auto-provisioning
├── docker-compose.yml              # mysql + redis + app services
├── Dockerfile                      # multi-stage: JDK 21 build → JRE 21 runtime
├── src/main/
│   ├── java/com/urlshortner/…      # application code
│   └── resources/
│       ├── application.yml         # Hikari, Lettuce, rate limit, cache tunings
│       ├── db/migration/V1__…sql   # Flyway schema
│       ├── openapi.yaml            # hand-authored spec (for reference)
│       └── static/index.html       # dev workbench UI
└── src/test/…                      # JUnit 5 + Mockito + Testcontainers
```

---

## Step 1 — Start infrastructure (MySQL 8.0 + Redis 7)

From the project root:

```bash
docker compose up -d mysql redis
```

Wait for both containers to become healthy:

```bash
docker compose ps
# Expect STATE=running and STATUS containing "(healthy)" for both.

docker compose exec mysql mysqladmin ping -uroot -prootpw
# Expect: mysqld is alive

docker compose exec redis redis-cli ping
# Expect: PONG
```

Healthchecks are baked into the compose file, so `docker compose up --wait` also works if you want to block until they're ready:

```bash
docker compose up -d --wait mysql redis
```

**Ports exposed on the host:** MySQL `3306`, Redis `6379`. See [Troubleshooting](#troubleshooting) if either port is already in use.

---

## Step 2 — Run the app locally (`./gradlew bootRun`)

With MySQL and Redis already up on the standard ports:

```bash
./gradlew bootRun
```

Virtual threads are enabled by default via `spring.threads.virtual.enabled=true` in `application.yml`.

**What happens on first startup:**

1. Foojay resolves and downloads JDK 21 into `~/.gradle/jdks/` (only if missing — subsequent runs skip this).
2. Gradle compiles and starts the Spring Boot app.
3. Flyway applies `V1__init_schema.sql` to the `urlshortener` database.
4. `IdSequenceInitializer` seeds `url:id:seq` in Redis to `max(238328, MAX(id))` so the first Base62 code has ≥4 characters.
5. Tomcat binds to `0.0.0.0:8080`.

You'll see a log line like:
```
Started UrlshortnerApplication in 2.0 seconds
ID sequence 'url:id:seq' aligned; MySQL max id=238328, Redis counter=238328
Tomcat started on port 8080 (http)
```

Stop the app with `Ctrl-C`.

---

## Step 3 — Run the full stack in Docker (`docker compose up --build`)

To spin the entire stack (app + MySQL + Redis) as containers:

```bash
docker compose up --build
```

The `app` service is built from `Dockerfile` (multi-stage: `eclipse-temurin:21-jdk-jammy` for the build, `eclipse-temurin:21-jre-jammy` for the runtime) and starts only after MySQL and Redis pass their healthchecks (`depends_on: condition: service_healthy`).

Detached:
```bash
docker compose up -d --build
```

Rebuild only the app image without touching the DBs:
```bash
docker compose up -d --build app
```

---

## Verification & application URLs

Once the app is up, the following URLs are live on `http://localhost:8080`:

| URL | Purpose |
|---|---|
| [`http://localhost:8080/`](http://localhost:8080/) | **Developer UI Workbench** — hand-testable forms for shorten + analytics with an RFC 7807 error banner |
| [`http://localhost:8080/swagger-ui/index.html`](http://localhost:8080/swagger-ui/index.html) | **Swagger UI** — interactive API console, auto-generated from controller annotations by Springdoc |
| [`http://localhost:8080/v3/api-docs`](http://localhost:8080/v3/api-docs) | Machine-readable OpenAPI JSON |
| [`http://localhost:8080/healthz`](http://localhost:8080/healthz) | Lightweight liveness probe (returns `{status:"UP", timestamp:...}`) |
| [`http://localhost:8080/actuator/health`](http://localhost:8080/actuator/health) | Full Actuator health (includes MySQL and Redis probes) |
| [`http://localhost:8080/actuator/prometheus`](http://localhost:8080/actuator/prometheus) | Prometheus scrape endpoint |

Quick sanity check with `curl`:

```bash
# Create a short URL
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/some/very/long/path"}'
# → 201 with {"shortCode":"1001","shortUrl":"http://localhost:8080/1001",...}

# Follow the redirect (verbose)
curl -sI http://localhost:8080/1001
# → HTTP/1.1 302 + Location: https://example.com/some/very/long/path

# Look up stats
curl -s http://localhost:8080/api/v1/urls/1001/stats
```

---

## Running tests

### Unit tests only (no Docker required)

```bash
./gradlew test --tests "com.urlshortner.util.*" --tests "com.urlshortner.service.UrlServiceTest"
```

Runs `Base62EncoderTest` (42 tests) and `UrlServiceTest` (8 tests). ~1 second on a warm daemon.

### Full test suite including Testcontainers integration tests

```bash
./gradlew check
```

Equivalent to `./gradlew test` — runs all 55 tests including `UrlShortenerIntegrationTest`, which:

1. Spins up a `mysql:8.0` and `redis:7-alpine` container via Testcontainers.
2. Applies Flyway migrations.
3. Runs the E2E flow (create → redirect → stats), an invalid-payload check, a duplicate-alias 409, and a 101-request rate-limit breach.

**Requires Docker to be running.** The integration test class alone takes ~20s (most of it container startup).

Run just the integration test:
```bash
./gradlew test --tests "com.urlshortner.UrlShortenerIntegrationTest"
```

Test reports land at `build/reports/tests/test/index.html`.

---

## Troubleshooting

### View application logs

**Full-stack (containerized app):**
```bash
docker compose logs -f app          # follow app logs
docker compose logs -f mysql redis  # follow DB logs
docker compose logs --tail=100      # last 100 lines from every service
```

**Local `bootRun`:** logs stream directly to your terminal. Timestamps use your local TZ.

### Reset containers and wipe volumes

Stop everything, remove containers, and **delete the persisted MySQL + Redis data volumes** (destructive):

```bash
docker compose down -v
```

Without wiping volumes (keeps DB data):
```bash
docker compose down
```

Just remove the app container but keep MySQL/Redis running:
```bash
docker compose rm -sf app
```

### Port conflicts

The default host ports are **3306** (MySQL), **6379** (Redis), and **8080** (app). If any is already bound:

```bash
# Discover who owns the port (macOS/Linux)
lsof -nP -iTCP:6379 -sTCP:LISTEN
lsof -nP -iTCP:3306 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

Common causes: a locally-installed Redis or MySQL, another `docker compose` project, an already-running Spring Boot instance from a previous session.

**Fix A — stop the competing process.** If it's your own container from another project, `docker stop <container>` for it. If it's a system-installed service:
```bash
# macOS (Homebrew)
brew services stop redis
brew services stop mysql

# Linux (systemd)
sudo systemctl stop redis
sudo systemctl stop mysql
```

**Fix B — remap the port in `docker-compose.yml`.** Change the left side of the `ports:` mapping to a free port. Example — bind MySQL to `3307`:

```yaml
mysql:
  ports:
    - "3307:3306"   # <-- was 3306:3306
```

Then either update `SPRING_DATASOURCE_URL` in `application.yml` (for local `bootRun`) or set it via env var:

```bash
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/urlshortener?useSSL=false&allowPublicKeyRetrieval=true \
  ./gradlew bootRun
```

The same pattern works for Redis (`SPRING_DATA_REDIS_PORT`) and the app (`SERVER_PORT`).

**Fix C — for the app port only,** override on the command line:
```bash
./gradlew bootRun --args='--server.port=8081'
```

### Flyway migration failed

Usually happens if the schema has been manually edited or you're switching branches with different `V1__…sql` content. Cleanest fix:

```bash
docker compose down -v   # wipes the mysql-data volume
docker compose up -d mysql redis
./gradlew bootRun        # Flyway starts fresh
```

### `Table 'urlshortener.urls' doesn't exist`

Flyway didn't run. Almost always means `spring-boot-starter-flyway` isn't on the classpath — check `build.gradle` still lists it under `dependencies`.

### Gradle can't find a Java 21 install

Ensure `settings.gradle` still contains:
```gradle
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
```
That plugin lets Gradle download JDK 21 into its user cache. First build with a fresh cache is slow (~30s); subsequent builds are instant.

### Testcontainers "Could not find a valid Docker environment"

Docker daemon isn't reachable. Start Docker Desktop (macOS/Windows) or `sudo systemctl start docker` (Linux). On Colima/Rancher/Podman, ensure `DOCKER_HOST` is set or the socket symlink at `/var/run/docker.sock` points at your runtime.

### The dev workbench UI counts against my rate limit

Yes — the rate-limit interceptor matches `/**`. Loading `/` costs 1 request from the per-IP quota (default 100/min). Not a bug; if it's inconvenient during heavy manual testing, either bump `app.ratelimit.limit` in `application.yml` or add `/` and `/index.html` to `WebMvcConfig`'s `excludePathPatterns`.

---

## Useful one-liners

| Task | Command |
|---|---|
| Format list of all URLs in DB | `docker compose exec mysql mysql -uroot -prootpw urlshortener -e "SELECT id, short_code, original_url, total_clicks FROM urls"` |
| Inspect current Redis keys | `docker compose exec redis redis-cli --scan` |
| Peek at the ID counter | `docker compose exec redis redis-cli GET url:id:seq` |
| Rebuild without cache | `docker compose build --no-cache app` |
| Tail app logs only | `docker compose logs -f app` |
| Full clean + fresh start | `docker compose down -v && docker compose up -d --build` |
