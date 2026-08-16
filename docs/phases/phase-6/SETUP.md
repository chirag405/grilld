# Phase 6 — Setup

Use the shared prerequisites and credentials from `docs/phases/phase-5/SETUP.md`. Phase 6 introduces no new required external credential.

## Required local services

```powershell
# Repo root: Postgres
docker compose up -d postgres

# Terminal 2: Python/LangGraph
cd grilld-ai-service
uv run langgraph dev --no-browser

# Terminal 3: Spring Boot (JDK 26)
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
$env:SPRING_PROFILES_ACTIVE = "local,python-ai-service"
./mvnw spring-boot:run
```

## New configuration

| Property | Default | Purpose |
|---|---|---|
| `grilld.generation.resume-sweep.interval-ms` | `300000` | Delay between stale-run scans. |
| `grilld.generation.resume-sweep.stale-after-ms` | `900000` | Age after which an in-progress run is retried. |
| `grilld.packages.local-storage-dir` | `./data/packages` | Local ZIP storage directory. Runtime contents are Git-ignored. |

Cost-circuit-breaker properties are documented in the application configuration and can be overridden with the corresponding Spring environment-variable form.

## Verification

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw test
```

Docker is required because the integration suite uses PostgreSQL Testcontainers. No running Python service or paid model call is needed for the automated Java gate.

For Python unit tests, `TAVILY_API_KEY` must be present because `TavilySearch` validates configuration when the specialists are imported, although the unit suite makes no Tavily request. A dummy value is sufficient for this offline gate:

```powershell
cd grilld-ai-service
$env:TAVILY_API_KEY = "test-only-not-used"
uv run pytest tests/unit_tests
```
