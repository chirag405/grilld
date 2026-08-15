# grilld-ai-service

Grilld's AI service - all agent/LLM logic lives here, never in the Spring Boot backend (`grilld-backend/`). Built on Deep Agents + LangGraph. See `docs/decisions-and-technical-architecture.md` §11 for the full architecture and reasoning, and `docs/README.md` for the whole project.

## What's here (Phase 3)

- `src/grilld_ai_service/graph.py` - the Orchestrator (`build_orchestrator`), Grilld's top-level Deep Agent. Currently has one deliberately trivial subagent (`ping`) proving `SubAgentMiddleware` delegation works; the real specialist roster (Tech Architect, Infra Agent, Roadmap Agent, etc.) is Phase 5.
- `src/grilld_ai_service/app.py` - wires the Postgres checkpointer (own tables, same DB instance as `grilld-backend`, never its business schema) and exposes the graph via `get_graph`, referenced by `langgraph.json`.
- No LangSmith managed cloud sandbox (unlike the `deep-agent-python` template this was scaffolded from) - Grilld self-hosts (`langgraph build` + `langgraph up`), so there's no LangSmith Platform execution runtime to sandbox against.

## Prerequisites

- Python 3.11+ (this project uses 3.12.4)
- [`uv`](https://docs.astral.sh/uv/) for dependency management
- `ANTHROPIC_API_KEY` - all LLM calls happen in this service
- The same local Postgres `grilld-backend` uses (`docker compose up -d postgres` from the repo root)

**Windows only:** `src/grilld_ai_service/__init__.py` sets the asyncio event loop policy at import time - psycopg's async mode (used by the Postgres checkpointer) can't run under Windows' default `ProactorEventLoop`. No-op on Linux/macOS.

## Quickstart

```bash
uv sync
cp .env.example .env   # fill in ANTHROPIC_API_KEY at minimum
uv run langgraph dev
```

## Tests

```bash
uv run pytest                          # unit tests always run
uv run pytest tests/integration_tests  # needs ANTHROPIC_API_KEY + Postgres up
```

- `tests/unit_tests/` - no external dependencies, no live DB, no API key.
- `tests/integration_tests/test_graph.py` - real Claude call, proves delegation to the `ping` subagent.
- `tests/integration_tests/test_checkpoint_persistence.py` - Phase 3's actual gate: two fully separate Postgres connections and orchestrator objects, same thread, proving checkpoint state survives what amounts to a process restart.

## Reference docs

- Deep Agents overview: https://docs.langchain.com/oss/python/deepagents/overview
- LangGraph persistence: https://docs.langchain.com/oss/python/langgraph/persistence
