"""Proves Phase 3's actual gate: checkpoint state survives a process restart,
not just "the agent runs once." Two fully separate AsyncPostgresSaver
connections and two separate orchestrator objects, same thread_id - simulates
the service process exiting and restarting between calls. Needs a real
Postgres reachable at DATABASE_URL (defaults to the local docker-compose
instance) and a real ANTHROPIC_API_KEY, since the assistant has to actually
recall the fact from checkpointed history, not just echo a tool result.
"""

import os

import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

from grilld_ai_service.app import DATABASE_URL
from grilld_ai_service.graph import build_orchestrator

pytestmark = pytest.mark.anyio

if not os.getenv("ANTHROPIC_API_KEY"):
    pytest.skip(
        "Set ANTHROPIC_API_KEY to run integration tests.", allow_module_level=True
    )


async def test_state_survives_a_simulated_restart() -> None:
    thread_id = "pytest-restart-survival"
    config = {"configurable": {"thread_id": thread_id}}

    # "Process 1": establish state, then close the connection entirely.
    async with AsyncPostgresSaver.from_conn_string(DATABASE_URL) as checkpointer:
        await checkpointer.setup()
        orchestrator = build_orchestrator(checkpointer=checkpointer)
        await orchestrator.ainvoke(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": (
                            "The secret word is PINEAPPLE. Acknowledge in one "
                            "short sentence, call no tools."
                        ),
                    }
                ]
            },
            config=config,
        )

    # "Process 2": brand new connection, brand new orchestrator object, same
    # thread_id - nothing here shares in-memory state with the block above.
    async with AsyncPostgresSaver.from_conn_string(DATABASE_URL) as checkpointer:
        await checkpointer.setup()
        orchestrator = build_orchestrator(checkpointer=checkpointer)
        result = await orchestrator.ainvoke(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": "What secret word did I just tell you? One short sentence.",
                    }
                ]
            },
            config=config,
        )

    final_answer = result["messages"][-1].content
    assert "PINEAPPLE" in final_answer.upper()
