# Learning Grilld's AI Service — A Running Log

You don't know Python/LangGraph/Deep Agents yet either. This is the Python-side counterpart to the root `LEARNING.md` (which covers `grilld-backend`, the Java/Spring Boot side). Split out starting Phase 5 because the combined file was getting hard to navigate once both sides had real depth - Java/Spring content stays in the root file, everything about `grilld-ai-service/` (LangGraph, Deep Agents, the specialist agents, Claude prompting) lives here from now on. Phases 3-4's Python work is summarized here too (copied from the root file, where the full original context still lives) so this file is a complete standalone story of the AI service on its own.

---

## The big picture

`grilld-ai-service/` is a separate Python program, not part of the Spring Boot app at all - see `docs/decisions-and-technical-architecture.md` §11 for the full reasoning. Spring Boot owns the database, auth, and billing; this service owns every actual AI/LLM call. They talk over plain HTTP - Spring calls a LangGraph server (`langgraph dev` in development) running this code, gets back structured JSON, and never runs any AI logic itself.

Built on two frameworks stacked together:
- **LangGraph** - for pieces that need precise, hand-tuned control flow (the Interrogator's vagueness-gated prompting, the Rubric/Scale Calibrator's single structured-output call).
- **Deep Agents** - for the specialist roster (Phase 5+), where delegation to focused subagents matters more than custom graph edges. Deep Agents is itself built on LangGraph underneath.

---

## Phase 3: the service comes alive

Scaffolded via `langgraph new --template deep-agent-python`, then trimmed: no LangSmith cloud sandbox (that template assumes deploying to LangSmith's managed platform; Grilld self-hosts), package renamed to `grilld_ai_service`.

- **`graph.py`** defines the Orchestrator - Grilld's top-level Deep Agent (`create_deep_agent`). Phase 3 gave it exactly one deliberately fake subagent (`ping`), whose only job was proving delegation actually works before building anything real on top.
- **`app.py`** wires a **Postgres checkpointer** (`AsyncPostgresSaver`) - LangGraph's mechanism for remembering a graph's state across calls, pointed at the same Postgres database `grilld-backend` uses, but writing only to its own tables (`checkpoints`, `checkpoint_blobs`, etc.), never Spring's business tables.

### Two servers, two very different jobs

LangGraph ships two ways to run a graph as a server, and confusing them cost real debugging time:

- **`langgraph dev`** - a lightweight local dev server. Deliberately **ephemeral**: its own thread/run bookkeeping lives in memory, not the database, so it restarts fast for quick iteration. This is what Spring actually talks to.
- **`langgraph up`** - a full, production-shaped server (a separate Go-based "Core API," not the same code as `dev`) with its own Postgres schema, that appeared to need a real LangSmith deployment license for full behavior.

The lesson: a checkpointer object constructed in your own Python code (proven to survive a restart via a direct script test and an automated pytest) is a *different thing* from whatever persistence the server wrapping your graph provides over HTTP. Both needed proving separately - only the first is proven so far; `langgraph up`'s fuller setup is deliberately deferred, real infra work rather than something to rush.

### Other things that came up

- **Windows can't run psycopg's async mode under its default event loop.** Fixed once, at package import time (`grilld_ai_service/__init__.py`), so every entry point gets it automatically. No-op on Linux/macOS.
- **The LangGraph server requires its graph-factory function's parameters to be explicitly typed** (`RunnableConfig`, `ServerRuntime`) so it can inspect and call them correctly - an untyped `def get_graph(config=None, runtime=None)` gets rejected outright, not silently ignored.
- **Creating the same LangGraph thread twice returns `409 Conflict`**, not a silent no-op - matters because Spring calls thread-creation on every turn, not just the first one for a session.

---

## Phase 4: the real Interrogator, and a Rubric Agent that argues back

### The Interrogator: two nodes, no script

`interrogator/graph.py` is a small `StateGraph` (not a Deep Agent - it needs precise custom control flow middleware doesn't give you) with exactly two nodes:

1. **`check_vagueness`** - a plain Python function, no LLM call. Regex-matches the last answer against a fixed vague-term list ("fast", "a lot", "eventually"...) using word-boundary matching so it doesn't false-positive on substrings like "fasten". Cheap and deterministic on purpose.
2. **`generate_turn`** - the actual Claude call, using `.with_structured_output(InterrogatorTurnResult)` so the response comes back as validated Pydantic data, never free text to parse by hand.

The prompt is built fresh every call from whatever Spring sends - no conversation history lives inside this service. That's what makes turn 40 cost the same tokens as turn 4.

### A judge that disagreed with itself

The Rubric Agent (`rubric/graph.py`) uses LangChain's `openevals` library to score a brief against six dimensions as `FAIL`/`BORDERLINE`/`PASS` - categorical, not 1-5, because fine-grained numeric LLM-as-judge scores are unreliable.

The first version also asked the same LLM call to produce an overall `verdict` alongside the six scores. Testing against a strong brief fixture turned up something worth remembering: the model gave zero FAILs and exactly one BORDERLINE, which by its own stated rule should accept - but its free-text verdict field said `probe_further` anyway. It didn't consistently apply its own rule to its own scores.

The fix: stop asking. The judge's `output_schema` now only returns the six scores; a plain Python function, `_compute_verdict()`, derives the verdict deterministically afterward. **When an LLM call's own structured output already contains everything needed to compute a downstream decision, compute it in code - don't ask the same call to also decide "holistically."** The two can disagree, and the free-form one is usually the less trustworthy of the two.

### Two ways to call the same LangGraph server

Threaded runs (`POST /threads/{id}/runs/wait`) suit anything conceptually part of an ongoing session, even a stateless one (the Interrogator - Grilld's session id doubles as the thread id). Stateless runs (`POST /runs/wait`, no thread) suit a one-shot judgment with no session concept at all (the Rubric Agent, later the Scale Calibrator too).

---

## Phase 5 (in progress): the specialist roster

The actual roster (`product-and-architecture.md` §3.2) is 11 agents - Scale Calibrator, Market Analyst, Competition Analyst, Strategy Agent, Tech Architect, Infra Agent, Diagram Agent, Roadmap Agent, Skills Curator, Agent-File Writer, Consistency Auditor - bigger than earlier planning's "7 agents" shorthand suggested.

### Web search needs a real API key

Market Analyst, Competition Analyst, Tech Architect, and Infra Agent all need live web search. The standard LangChain integration is Tavily (`langchain-tavily`'s `TavilySearch` tool), needing its own API key - same category of real external dependency as Google OAuth and Anthropic. Got a real key from tavily.com's free tier (1,000 searches/month) rather than skipping search or faking it.

### "Ask the user" needed a scope decision

Tech Architect and Infra Agent are spec'd to interrupt mid-run and ask 1-2 targeted questions. Building that *for real* means a full interrupt → surface-to-user → wait → resume round trip through Spring's API - work the existing phase plan already scheduled for Phase 6 (Run Report + SSE + resume sweep). Rather than deciding this alone, this got asked directly: pull Phase 6's plumbing forward now, or stub it? Answer: stub it - Phase 5's asking agents make their best-reasoned assumption and record it explicitly (feeding `ASSUMPTIONS.md` later) instead of actually blocking. Keeps Phase 5 scoped to "agents produce correct artifacts," avoids building interrupt/resume plumbing twice.

### `create_deep_agent`'s default file storage already does what's needed

Before writing any specialist agent: how do file-writing agents (Diagram Agent, Infra Agent, Agent-File Writer, Skills Curator) share output with each other? The Consistency Auditor needs to *read* every doc the others wrote. The instinct was to explicitly configure a `FilesystemBackend(virtual_mode=True)`. Reading `create_deep_agent`'s actual source first showed this wasn't necessary: with no `backend` given, it defaults to `StateBackend` - files live in the *shared graph state* (`state["files"]`, a `dict[str, FileData]`), not a separate per-agent copy. That same `backend` reference threads through the top-level `FilesystemMiddleware` **and** `SubAgentMiddleware` uniformly, so a file one subagent writes is visible to the Orchestrator and every other subagent in the same run, free, no extra config. General lesson: check what a framework's *unconfigured default* actually does before hand-configuring something it may already handle - added config on top can just be redundant complexity.

### Scale Calibrator: its own graph, not a subagent

Every other specialist is a Deep Agents subagent (a system prompt + tool list, delegated to via the Orchestrator's `task` tool) - Scale Calibrator isn't, on purpose. Its output (a T0-T3 tier) is structured data Spring needs to *store and show the user for override* before the full, expensive generation run even starts (§4: "the tier is user-visible and overridable"). That needs its own small, quickly-callable graph (same shape as the Rubric Agent: one node, `.with_structured_output()`, no thread) rather than a step buried inside one long Orchestrator invocation Spring can't see the middle of.
