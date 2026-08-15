"""The specialist roster (Phase 5, docs/product-and-architecture.md §3.2).

Every agent here is a plain Deep Agents subagent - a system prompt plus a
scoped tool list, delegated to via the Orchestrator's `task` tool
(SubAgentMiddleware). None of these need bespoke Python control flow the way
the Interrogator does, so unlike interrogator/ or rubric/, there's no
per-agent graph.py - just a SubAgent dict per agent, grouped into modules by
where they sit in the orchestration flow (product-and-architecture.md §3.3).

Every subagent writes its output as a file via the filesystem tools every
Deep Agent gets automatically (write_file, read_file, ls, ...) - see
tools.py's module docstring... actually see graph.py's build_orchestrator:
no explicit backend is configured, so every subagent shares the same
in-state virtual filesystem as the Orchestrator and each other, for free.

Model selection: every specialist gets its model tier from
model_tiers.model_for() (Phase 5's edge-case retro) rather than a hardcoded
string - see graph.py's SPECIALIST_ROSTER construction.
"""

from __future__ import annotations

# Every specialist's final message doubles as its narration
# (decisions-and-technical-architecture.md §10.2) - not a separate LLM call,
# just a requirement on the same final report every specialist already
# produces. Read directly off the Orchestrator's own SSE stream (the "tools"
# event correlated to this specialist's task tool_call_id) rather than
# parsed out of a raw summary - see grilld-backend's SseGenerationStream.
NARRATION_INSTRUCTION = """
End your final response with exactly one narration sentence in this shape: what you did and why - \
not just a restatement of the artifact ("wrote TECH_STACK.md") but the actual reasoning behind it \
("chose Postgres over Firebase since the brief needs relational queries on the invoice/client \
join"). This sentence is shown to the user live while you work - make it worth reading.
"""
