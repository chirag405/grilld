# Phase 4 — Setup

No new external services this phase. Same prerequisites as `docs/phases/phase-3/SETUP.md` (Docker Postgres, `uv`, `ANTHROPIC_API_KEY`), plus one new Python dependency pulled in via `uv sync`.

## New this phase

| Package | Why |
|---|---|
| `openevals>=0.1.0` | The Rubric Agent's LLM-as-judge factory (`create_async_llm_as_judge`). Pulls in `langchain-openai`/`openai` as a transitive dependency even though Grilld only uses the Anthropic judge - harmless, no `OPENAI_API_KEY` needed unless you explicitly switch the judge model. |

```powershell
cd grilld-ai-service
uv sync   # picks up openevals from pyproject.toml automatically
```

## Running Phase 4 locally

Identical to Phase 3's run instructions - `langgraph.json` now registers two more graphs (`interrogator`, `rubric`) alongside `orchestrator`; `langgraph dev` picks all three up automatically, nothing extra to configure.

```powershell
# 1. Postgres
docker compose up -d postgres

# 2. The Python AI service
cd grilld-ai-service
uv run langgraph dev --no-browser
# Runs on http://localhost:2024 - confirm all three graphs registered:
# curl http://localhost:2024/ok  ->  {"ok":true}

# 3. Spring Boot, pointed at the real Python service
cd ../grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"   # see phase-1 SETUP - JAVA_HOME can silently point at a stale JDK
$env:SPRING_PROFILES_ACTIVE = "local,python-ai-service"
$env:GOOGLE_CLIENT_ID = "..."      # see docs/phases/phase-1/SETUP.md
$env:GOOGLE_CLIENT_SECRET = "..."
./mvnw spring-boot:run
```

**Reminder from Phase 1, relevant again this phase:** the JWT signing key is ephemeral and regenerates on every Spring Boot restart. Any JWT obtained before a restart stops working - re-authenticate via `/oauth2/authorization/google` to get a fresh one before testing manually.

**Model cost note (unchanged from Phase 3):** `GRILLD_AI_MODEL` in `grilld-ai-service/.env` controls which Claude model both the Interrogator and Rubric Agent use. This phase's testing used Haiku throughout to keep iteration cheap; switch to a stronger model for anything where interview/rubric quality actually matters (e.g. before a real demo).

## Running the automated tests

**Python:**
```powershell
cd grilld-ai-service
uv run pytest                          # unit tests, no external deps
uv run pytest tests/integration_tests  # needs ANTHROPIC_API_KEY + Postgres up (checkpoint test only)
```

**Java:** unchanged - `./mvnw test` from `grilld-backend/`, no Python service needed (tests use `StubAiServiceClient` and a mocked `AiServiceClient`, never the real HTTP client).
