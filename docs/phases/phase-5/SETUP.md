# Phase 5 — Setup

Same prerequisites as `docs/phases/phase-4/SETUP.md`, plus two new external services.

## New this phase

| Credential | Why | Where to get it |
|---|---|---|
| `TAVILY_API_KEY` | Market Analyst, Competition Analyst, Tech Architect, and Infra Agent all use live web search | tavily.com → sign up → Dashboard → API Keys. Free tier: 1,000 searches/month. |
| `GROQ_API_KEY` (optional) | A genuinely free (rate-limited, not credit-limited) alternative to `ANTHROPIC_API_KEY` for local testing | console.groq.com → API Keys. No credit card needed. |

```powershell
cd grilld-ai-service
uv sync   # picks up langchain-tavily and langchain-groq automatically
```

**Using Groq instead of Claude for testing:** set `GRILLD_AI_MODEL=groq:llama-3.3-70b-versatile` in `.env` - every graph reads this one env var, so it's a config swap, not a code change. Confirmed working for structured-output graphs (Scale Calibrator, Rubric, Interrogator). **Not** currently reliable for the full specialist roster, since `market_analyst` (and the other web-search agents) need real tool-calling, which this Groq model doesn't handle reliably in an agent loop - see `TESTING.md`'s "Groq works for structured-output-only graphs" note. Switch back to `anthropic:claude-haiku-4-5-20251001` (or stronger) for anything that needs live web search.

## Running Phase 5 locally

Identical to Phase 4's run instructions - `langgraph.json` now also registers `scale_calibrator`; `langgraph dev` picks it up automatically alongside `orchestrator`, `interrogator`, and `rubric`.

```powershell
# 1. Postgres
docker compose up -d postgres

# 2. The Python AI service
cd grilld-ai-service
uv run langgraph dev --no-browser

# 3. Spring Boot
cd ../grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
$env:SPRING_PROFILES_ACTIVE = "local,python-ai-service"
$env:GOOGLE_CLIENT_ID = "..."
$env:GOOGLE_CLIENT_SECRET = "..."
./mvnw spring-boot:run
```

**New endpoints this phase**, once a session has concluded (Phase 4's rubric gate accepted):

```
POST /api/v1/sessions/{id}/scale-tier          # run calibration
PUT  /api/v1/sessions/{id}/scale-tier           # { "tier": "T2" } - user override
POST /api/v1/sessions/{id}/generate             # full roster run (needs scale-tier set first)
```

The generate call blocks until the whole roster finishes - real minutes for a real run against Claude, since Phase 5 uses the same synchronous call pattern as the Interrogator/Rubric Agent rather than the async run/stream pattern that's Phase 6 scope.

## Running the automated tests

**Python:**
```powershell
cd grilld-ai-service
uv run pytest tests/unit_tests                              # no external deps
uv run pytest tests/integration_tests --ignore=tests/integration_tests/test_graph.py
                                                              # needs ANTHROPIC_API_KEY or GROQ_API_KEY
uv run pytest tests/integration_tests/test_graph.py          # the expensive full-roster test -
                                                              # needs ANTHROPIC_API_KEY specifically
                                                              # (see TESTING.md - Groq doesn't reliably
                                                              # handle this one's tool-calling agents),
                                                              # and costs real API spend - run deliberately,
                                                              # not as part of routine iteration.
```

**Java:** unchanged - `./mvnw test` from `grilld-backend/`, no Python service needed.
