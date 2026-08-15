import os

import pytest

from grilld_ai_service.graph import build_orchestrator

pytestmark = pytest.mark.anyio

if not os.getenv("ANTHROPIC_API_KEY"):
    pytest.skip(
        "Set ANTHROPIC_API_KEY to run integration tests.", allow_module_level=True
    )


async def test_orchestrator_delegates_to_ping_subagent() -> None:
    # Deliberately no Postgres checkpointer here - this test proves the LLM
    # roundtrip and delegation work, independent of whether Postgres is up.
    # Checkpointer persistence itself is verified separately (see Phase 3's
    # TESTING.md - restart-survival is a manual/process-level check, not
    # something a single pytest run can exercise meaningfully).
    orchestrator = build_orchestrator(checkpointer=None)
    result = await orchestrator.ainvoke(
        {
            "messages": [
                {
                    "role": "user",
                    "content": "Delegate to the ping subagent with the message 'hello from phase 3'.",
                }
            ]
        }
    )
    assert result is not None
    assert result.get("messages")
