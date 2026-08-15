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

Model selection: every agent here deliberately inherits the Orchestrator's
default model (GRILLD_AI_MODEL) rather than hardcoding the roster table's
Opus/Sonnet split from product-and-architecture.md §3.2. That per-agent
tuning is real but not free to validate (it means real API calls on a
stronger, pricier model) - deferred until there's a reason to spend on it,
same reasoning that's kept this whole project on Haiku during development.
"""
