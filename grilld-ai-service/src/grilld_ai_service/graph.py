"""Grilld's top-level Deep Agent - the Orchestrator from
docs/decisions-and-technical-architecture.md §11.1.

Phase 5: the real specialist roster (product-and-architecture.md §3.2)
replaces the Phase 3 placeholder `ping` subagent. Delegation, flow ordering,
and per-agent scoped tools are all proven now, not just "a subagent exists."
The Interrogator/Rubric Agent/Scale Calibrator are separate top-level graphs
(interrogator/, rubric/, scale_calibrator/), not part of this roster - see
each package's own docstring for why.

Unlike the deep-agent-python template this was scaffolded from, this does NOT
use LangSmith's managed cloud sandbox (deepagents.backends.sandbox) - Grilld is
self-hosted (decisions-and-technical-architecture.md §11.3), so there's no
LangSmith Platform execution runtime to sandbox against. The default in-process
backend (StateBackend, since no `backend` is passed to create_deep_agent) is
what every specialist agent actually needs: they write structured documents
into shared graph state, not arbitrary shell commands against a real
filesystem - and that same default is what makes one subagent's file writes
visible to the Orchestrator and every other subagent for free, no extra wiring.
"""

from __future__ import annotations

from deepagents import create_deep_agent

from grilld_ai_service.model_tiers import model_for
from grilld_ai_service.specialists.agent_kit import AGENT_FILE_WRITER
from grilld_ai_service.specialists.audit import CONSISTENCY_AUDITOR
from grilld_ai_service.specialists.delivery import ROADMAP_AGENT, SKILLS_CURATOR
from grilld_ai_service.specialists.diagram import DIAGRAM_AGENT
from grilld_ai_service.specialists.market import COMPETITION_ANALYST, MARKET_ANALYST, STRATEGY_AGENT
from grilld_ai_service.specialists.tech import INFRA_AGENT, TECH_ARCHITECT

# Flow order per product-and-architecture.md §3.3. The diagram draws the
# market branch and tech branch as a parallel fan-out, but every agent that
# can ask the user must run sequentially (§3.1 - no two asking agents
# interrupting at once), and true parallel dispatch needs orchestration
# machinery (Send-style fan-out coordination) this Phase doesn't build -
# Phase 6's territory alongside the rest of orchestration robustness. Running
# the whole roster sequentially, in this dependency-respecting order, is
# correct output today; parallelizing the non-asking branches is a latency
# optimization for later, not a correctness requirement now.
ORCHESTRATOR_SYSTEM_PROMPT = """
You are Grilld's Orchestrator. You delegate work to specialist subagents and combine their
results - you do not do specialist work yourself.

You will be given a project's brief (as JSON) and its assigned scale tier (T0-T3). Your first
action, before delegating to anyone: write the brief to /docs/PROJECT_BRIEF.md (a readable
distillation, not raw JSON dumped verbatim) and note the scale tier at the top of that file - the
scale tier is the first thing every subagent needs and a hard ceiling on every recommendation
they make.

If your instructions list facts the interview never resolved (the user either hit the "just
generate it" escape hatch or the interview ended before the Interrogator could ask about them),
write every one of them into /docs/ASSUMPTIONS.md as its own line, right after PROJECT_BRIEF.md
and before delegating to anyone - this must exist and be prominent, not buried. Specialists that
discover their own assumptions later (Tech Architect, Infra Agent - see their own instructions)
append to this same file rather than overwrite it.

Then delegate to every subagent in this exact order, using the `task` tool. Each one already
knows how to read what previous agents wrote via the shared filesystem (ls, read_file) - you
don't need to re-paste prior output into their instructions, just tell each one to proceed and
remind them of the scale tier. Tell each one, once, to reference other docs by filename where it
genuinely helps the reader (e.g. "see TECH_STACK.md for the full reasoning") rather than repeating
another doc's content - Grilld's output is a connected package, not independent documents that
happen to ship together.

1. market_analyst
2. competition_analyst
3. strategy_agent
4. tech_architect
5. infra_agent
6. diagram_agent
7. roadmap_agent
8. skills_curator
9. agent_file_writer
10. consistency_auditor

Run them in this order, one at a time - do not skip any, and do not run two at once. After the
last one finishes, report a summary: every file that was written (use ls to check) and whether
consistency_auditor found any issues.
""".strip()

_RAW_ROSTER = [
    MARKET_ANALYST,
    COMPETITION_ANALYST,
    STRATEGY_AGENT,
    TECH_ARCHITECT,
    INFRA_AGENT,
    DIAGRAM_AGENT,
    ROADMAP_AGENT,
    SKILLS_CURATOR,
    AGENT_FILE_WRITER,
    CONSISTENCY_AUDITOR,
]

# Each specialist dict as authored in specialists/*.py has no "model" key -
# model_tiers.AGENT_TIER (product-and-architecture.md §3.2) decides that
# centrally, here, rather than every specialist file hardcoding its own tier.
SPECIALIST_ROSTER = [{**agent, "model": model_for(agent["name"])} for agent in _RAW_ROSTER]


def build_orchestrator(checkpointer=None):
    """Builds Grilld's top-level Deep Agent.

    checkpointer is injected by app.py (the LangGraph server graph factory) so
    this module stays testable without a live Postgres connection.
    """
    return create_deep_agent(
        model=model_for("orchestrator"),
        system_prompt=ORCHESTRATOR_SYSTEM_PROMPT,
        subagents=SPECIALIST_ROSTER,
        checkpointer=checkpointer,
        name="grilld_orchestrator",
    )
