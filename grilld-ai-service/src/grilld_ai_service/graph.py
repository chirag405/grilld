"""Grilld's top-level Deep Agent - the Orchestrator from
docs/decisions-and-technical-architecture.md §11.1.

Phase 3 scope only: prove the Deep Agents orchestrator boots, delegates to a
subagent via SubAgentMiddleware, and runs against a Postgres checkpointer that
survives a process restart. The real specialist roster (Tech Architect, Infra
Agent, Diagram Agent, Roadmap Agent, Skills Curator, Agent-File Writer,
Consistency Auditor) is Phase 5; the Interrogator's own LangGraph StateGraph
subgraph is Phase 4. `ping_agent` below is a deliberately trivial stand-in for
"a subagent exists and delegation works," not a real specialist.

Unlike the deep-agent-python template this was scaffolded from, this does NOT
use LangSmith's managed cloud sandbox (deepagents.backends.sandbox) - Grilld is
self-hosted (decisions-and-technical-architecture.md §11.3), so there's no
LangSmith Platform execution runtime to sandbox against. The default in-process
backend is what every specialist agent actually needs: they write structured
documents, not arbitrary shell commands.
"""

from __future__ import annotations

import os

from deepagents import create_deep_agent
from langchain_core.tools import tool

DEFAULT_MODEL = os.getenv("GRILLD_AI_MODEL", "anthropic:claude-sonnet-4-6")

ORCHESTRATOR_SYSTEM_PROMPT = """
You are Grilld's Orchestrator. You delegate work to specialist subagents and
combine their results - you do not do specialist work yourself.

For now (Phase 3), your only subagent is `ping`, used solely to prove
delegation works end to end. Real specialists (Tech Architect, Infra Agent,
Roadmap Agent, and the rest of the roster from
docs/product-and-architecture.md §3.2) are added in Phase 5.
""".strip()


@tool
def echo(message: str) -> str:
    """Echo the given message back. Used only to prove a subagent can call a tool."""
    return f"ping received: {message}"


PING_SUBAGENT = {
    "name": "ping",
    "description": "Trivial subagent that proves SubAgentMiddleware delegation works. Not a real specialist.",
    "system_prompt": (
        "You are a trivial test subagent. Call the echo tool with the exact "
        "message you were given, then report back what it returned."
    ),
    "tools": [echo],
}


def build_orchestrator(checkpointer=None):
    """Builds Grilld's top-level Deep Agent.

    checkpointer is injected by app.py (the LangGraph server graph factory) so
    this module stays testable without a live Postgres connection.
    """
    return create_deep_agent(
        model=DEFAULT_MODEL,
        system_prompt=ORCHESTRATOR_SYSTEM_PROMPT,
        subagents=[PING_SUBAGENT],
        checkpointer=checkpointer,
        name="grilld_orchestrator",
    )
