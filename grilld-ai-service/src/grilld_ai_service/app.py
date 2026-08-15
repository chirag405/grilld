"""Graph factory referenced by langgraph.json.

Wires the Postgres checkpointer - its own tables (checkpoints, checkpoint_blobs,
checkpoint_writes, checkpoint_migrations), same Postgres instance as Spring
Boot's, never the business schema Spring owns
(docs/decisions-and-technical-architecture.md §11.2). This is what makes an
in-flight/paused graph survive a restart of this service - Phase 3's actual
gate, not just "the agent runs once."

Uses AsyncPostgresSaver, not the sync PostgresSaver: the LangGraph server (and
Deep Agents generally) invoke graphs via the async API (ainvoke/astream), which
requires an async-capable checkpointer - the sync one raises NotImplementedError
on the async methods.
"""

from __future__ import annotations

import contextlib
import os

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph_sdk.runtime import ServerRuntime

from grilld_ai_service.graph import build_orchestrator

# Same default as grilld-backend's application-local.properties, so both
# services point at the one local docker-compose Postgres out of the box.
DATABASE_URL = os.getenv(
    "DATABASE_URL", "postgresql://grilld:grilld_dev_only@localhost:5432/grilld"
)


@contextlib.asynccontextmanager
async def get_graph(config: RunnableConfig, runtime: ServerRuntime):
    """Async graph factory - the LangGraph server requires this exact
    signature shape (only RunnableConfig/ServerRuntime-typed params) to
    classify and call it correctly."""
    async with AsyncPostgresSaver.from_conn_string(DATABASE_URL) as checkpointer:
        await checkpointer.setup()  # idempotent - CREATE TABLE IF NOT EXISTS
        yield build_orchestrator(checkpointer=checkpointer)
