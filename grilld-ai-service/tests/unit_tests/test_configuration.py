from langgraph.pregel import Pregel

from grilld_ai_service.graph import ORCHESTRATOR_SYSTEM_PROMPT, PING_SUBAGENT, build_orchestrator


def test_orchestrator_compiles_without_a_live_database() -> None:
    # No checkpointer needed for compilation itself - only for actually running
    # a thread with persistence. Keeps this test independent of Postgres being up.
    orchestrator = build_orchestrator(checkpointer=None)
    assert isinstance(orchestrator, Pregel)


def test_ping_subagent_configured() -> None:
    assert PING_SUBAGENT["name"] == "ping"
    assert PING_SUBAGENT["tools"], "ping subagent must have at least the echo tool"


def test_system_prompt_is_nonempty() -> None:
    assert len(ORCHESTRATOR_SYSTEM_PROMPT.strip()) > 0
