# Agents

The Orchestrator (Grilld's top-level Deep Agent) is defined in `src/grilld_ai_service/graph.py`, via `build_orchestrator()`. The Postgres checkpointer is wired in `app.py`, not `graph.py` - keep `graph.py` free of any live DB dependency so `build_orchestrator(checkpointer=None)` stays usable in unit tests without Postgres running.

## Conventions

- Prefer async-native code (`ainvoke`, `async def`) - the checkpointer (`AsyncPostgresSaver`) only implements the async API; the sync one raises `NotImplementedError` if you try to use it with `ainvoke`.
- This service never writes to `grilld-backend`'s business tables (`project_briefs`, `slots`, etc.) - only to its own checkpoint tables. See `docs/decisions-and-technical-architecture.md` §11.2 for why, before adding any new persistence here.
- No LangSmith managed cloud sandbox / remote execution - Grilld self-hosts. Specialist agents write structured documents, not arbitrary shell commands; they don't need a sandboxed `execute` tool.
- On Windows, `__init__.py` sets the asyncio event loop policy at import time (psycopg async + `ProactorEventLoop` don't mix). Don't remove it without checking `uv run pytest tests/integration_tests/test_checkpoint_persistence.py` still passes on Windows.
