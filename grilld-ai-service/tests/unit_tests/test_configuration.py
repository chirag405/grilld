from langgraph.pregel import Pregel

from grilld_ai_service.graph import ORCHESTRATOR_SYSTEM_PROMPT, SPECIALIST_ROSTER, build_orchestrator


def test_orchestrator_compiles_without_a_live_database() -> None:
    # No checkpointer needed for compilation itself - only for actually running
    # a thread with persistence. Keeps this test independent of Postgres being up.
    orchestrator = build_orchestrator(checkpointer=None)
    assert isinstance(orchestrator, Pregel)


def test_full_specialist_roster_configured() -> None:
    names = {agent["name"] for agent in SPECIALIST_ROSTER}
    assert names == {
        "market_analyst",
        "competition_analyst",
        "strategy_agent",
        "tech_architect",
        "infra_agent",
        "diagram_agent",
        "roadmap_agent",
        "skills_curator",
        "agent_file_writer",
        "consistency_auditor",
    }
    for agent in SPECIALIST_ROSTER:
        assert agent["system_prompt"].strip(), f"{agent['name']} has an empty system prompt"


def test_every_specialist_carries_the_narration_requirement() -> None:
    # decisions-and-technical-architecture.md §10.2 - the Run Report needs a
    # narration line from every specialist, not just some of them.
    for agent in SPECIALIST_ROSTER:
        assert "narration sentence" in agent["system_prompt"], (
            f"{agent['name']} is missing NARRATION_INSTRUCTION"
        )


def test_system_prompt_is_nonempty() -> None:
    assert len(ORCHESTRATOR_SYSTEM_PROMPT.strip()) > 0
