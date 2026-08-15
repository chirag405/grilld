# Phase 3 — Setup

## Prerequisites

New this phase:

| Tool | Version | Needed for |
|---|---|---|
| Python | 3.12.4 (already present) | Running the AI service |
| [`uv`](https://docs.astral.sh/uv/) | 0.12.5, installed this session | Python dependency management |
| `ANTHROPIC_API_KEY` | — | All LLM calls happen in this service now |

Still needed from earlier phases: Docker (Postgres), the same local Postgres `grilld-backend` uses.

## Getting an Anthropic API key

console.anthropic.com → API Keys → Create Key. Costs real money per call - the whole session's testing used `claude-haiku-4-5-20251001` specifically to keep this cheap during development; switch to a stronger model via `GRILLD_AI_MODEL` once real agent logic (Phase 4+) needs better reasoning.

## Running Phase 3 locally

```powershell
# 1. Postgres (same as always)
docker compose up -d postgres

# 2. The Python AI service
cd grilld-ai-service
uv sync
cp .env.example .env   # fill in ANTHROPIC_API_KEY
uv run langgraph dev --no-browser
# Runs on http://localhost:2024

# 3. Spring Boot, now pointed at the real Python service
cd ../grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
$env:SPRING_PROFILES_ACTIVE = "local,python-ai-service"
$env:GOOGLE_CLIENT_ID = "..."      # see docs/phases/phase-1/SETUP.md
$env:GOOGLE_CLIENT_SECRET = "..."
./mvnw spring-boot:run
```

Without the `python-ai-service` profile active, Spring falls back to `StubAiServiceClient` automatically - no code changes needed to go back to canned responses for faster iteration on anything that doesn't need the real AI.

## Running the automated tests

**Python:**
```powershell
cd grilld-ai-service
uv run pytest                          # unit tests, no external deps
uv run pytest tests/integration_tests  # needs ANTHROPIC_API_KEY + Postgres up
```

**Windows note:** `src/grilld_ai_service/__init__.py` handles the asyncio event-loop fix automatically - no extra setup needed, just don't remove that file's contents.

**Java:** unchanged from Phase 2 - `./mvnw test` from `grilld-backend/`, no Python service needed (the existing tests use the stub).
