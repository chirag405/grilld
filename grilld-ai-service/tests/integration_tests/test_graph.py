"""Real Claude calls through the whole specialist roster - Phase 5's actual
gate ("a full generation run produces every expected doc artifact"). Slow
(10 sequential subagent calls, several with live web search) and costs real
API credits - this is the one test in the suite that's expensive on purpose,
because it's the only thing that proves the full pipeline holds together
end to end rather than each agent working in isolation.
"""

import os

import pytest

from grilld_ai_service.graph import build_orchestrator

pytestmark = pytest.mark.anyio

if not os.getenv("ANTHROPIC_API_KEY"):
    pytest.skip(
        "Set ANTHROPIC_API_KEY to run integration tests.", allow_module_level=True
    )

TEST_BRIEF = """{
  "problem_statement": "Solo developers lose track of which of their side-project ideas are
    actually worth building and end up abandoning most of them.",
  "target_user": "Solo indie hackers with several half-finished repos.",
  "scale_expectation": "100-200 users in the first 3 months.",
  "team_shape": "Just one person, a side project.",
  "builder_skillset": "Comfortable with React and Node, no mobile experience.",
  "hard_constraints": "No budget beyond a cheap VPS.",
  "success_definition": "A quick go/no-go score the builder actually trusts.",
  "risk_1": "The scoring could feel arbitrary and get ignored after a few uses.",
  "risk_2": "Building this instead of an actual side project."
}"""


async def test_full_roster_produces_every_expected_doc() -> None:
    # No Postgres checkpointer - this proves the LLM roundtrip and delegation
    # work, independent of whether Postgres is up (checkpointer persistence
    # itself is proven separately, see Phase 3's TESTING.md).
    orchestrator = build_orchestrator(checkpointer=None)
    result = await orchestrator.ainvoke(
        {
            "messages": [
                {
                    "role": "user",
                    "content": (
                        f"Here is the project brief (JSON):\n{TEST_BRIEF}\n\n"
                        "The assigned scale tier is T1 (Solo Indie / MVP). Begin."
                    ),
                }
            ]
        },
        config={"recursion_limit": 150},
    )

    files = result.get("files", {})
    written_paths = set(files.keys())

    fixed_paths = {
        "/docs/PROJECT_BRIEF.md",
        "/docs/MARKET_ANALYSIS.md",
        "/docs/COMPETITION.md",
        "/docs/STRATEGY.md",
        "/docs/TECH_STACK.md",
        "/docs/ARCHITECTURE.md",
        "/docs/INFRA.md",
        "/diagrams/architecture.mmd",
        "/diagrams/data-flow.mmd",
        "/diagrams/deployment.mmd",
        "/docs/ROADMAP.md",
        "/docs/SKILLS_NEEDED.md",
        "/agent-kit/AGENTS.md",
        "/agent-kit/CLAUDE.md",
        "/docs/CONSISTENCY_REPORT.md",
    }
    missing = fixed_paths - written_paths
    assert not missing, f"missing expected doc artifacts: {missing}\nactually wrote: {written_paths}"

    skill_files = [p for p in written_paths if p.startswith("/agent-kit/skills/") and p.endswith("SKILL.md")]
    assert skill_files, f"expected at least one per-phase skill file, wrote: {written_paths}"

    agent_role_files = [p for p in written_paths if p.startswith("/agent-kit/agents/") and p.endswith(".md")]
    assert agent_role_files, f"expected at least one per-role agent definition, wrote: {written_paths}"

    # Every doc should have real content, not an empty stub the agent forgot to fill in.
    for path in fixed_paths:
        content = "\n".join(files[path]["content"])
        assert len(content.strip()) > 50, f"{path} looks empty/stub: {content!r}"
