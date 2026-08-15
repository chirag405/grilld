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

### The full roster, wired - and a genuinely useful free-model discovery

All 10 specialists became `SubAgent` dicts (name/description/system_prompt/tools) in `specialists/`, registered on the Orchestrator in `graph.py`, replacing the Phase 3 `ping` placeholder entirely. The Orchestrator's system prompt now spells out the exact delegation order from `product-and-architecture.md` §3.3 as a numbered list - the diagram draws a parallel fan-out (market branch alongside tech branch), but true parallel dispatch needs real coordination machinery this phase doesn't build, and every asking-capable agent has to run sequentially anyway (§3.1) - so the whole roster just runs sequentially for now, correct output today, a latency optimization for later.

The 10-agent full run is expensive to test for real (10 sequential Claude calls, several with live Tavily search) - expensive enough that mid-testing, cost became a real, explicitly-raised concern. That led to trying **Groq** (`langchain-groq`, `groq:llama-3.3-70b-versatile`) as a genuinely free swap-in - free tier, no credit card, just rate-limited, and zero code changes needed since every graph already reads its model from the `GRILLD_AI_MODEL` env var.

It partially worked, which is itself the useful finding: Groq handled every **structured-output-only** graph perfectly (Scale Calibrator, Rubric, Interrogator - all pass against it, proving those graphs are genuinely model-agnostic), but broke on the very first **tool-calling** agent in the roster (`market_analyst`, calling `tavily_search`) - `groq.BadRequestError: tool_use_failed`, the model emitting a malformed function-call as literal text (`<function=tavily_search(...)></function>`) instead of a real structured tool call. Worth internalizing as a general lesson, not just a Groq-specific footnote: **"this model supports structured output" and "this model reliably calls tools inside a multi-step agent loop" are two separate capability claims.** A model can be excellent at one and unreliable at the other - test each capability a graph actually needs, don't assume one implies the other. Because of this, the full 10-agent live run was deliberately not completed this session (real Claude cost, weighed against the phase's other coverage, decided not to spend rather than assumed away) - see `docs/phases/phase-5/TESTING.md` for exactly what's proven vs. still open.

### Retro: vagueness detection, model selection, and open-ended user requests

A direct retro question - "are we actually handling every edge case a real user will throw at this?" - surfaced three real things worth fixing, plus one worth researching before touching any code (per an explicit "don't trust instinct, go check" instruction).

**Vagueness detection was deterministic-only, and that's a real gap, not just a style choice.** `check_vagueness`'s fixed term list ("fast", "scalable", "a lot"...) is a deliberate, documented design choice (interrogation-engine.md's own architecture calls for a cheap deterministic pre-check) - and checking current research confirmed hybrid deterministic-plus-LLM-judgment is a genuinely established pattern (Hebbia's agent-eval framework, Salesforce Agentforce's "guided determinism," an arXiv paper on LLM ambiguity resolution all use exactly this classify-then-resolve shape). But the bug was that the fixed list was the *only* signal - anything vague outside it ("seamless," "as needed," "some users") sailed through untouched. Fixed by adding a standing instruction: even when the deterministic check finds nothing, the model must still apply its own judgment as a backstop. The deterministic list stays as a fast, guaranteed-catch layer; it's no longer the ceiling on what counts as vague.

**Model selection was one flat model for every agent, when the spec already said not to be.** `product-and-architecture.md` §3.2's roster table assigns a tier (Opus/Sonnet) to every agent - this had simply never been wired up, everything inherited one `GRILLD_AI_MODEL`. Researched before implementing: dynamic ML-classifier-based routers (RouteLLM, vLLM Semantic Router) exist and are genuinely sophisticated, but they solve a *different* problem - routing a large volume of unpredictable incoming queries at runtime. Grilld's roster is a small, fixed, *known* set of agent roles, each with an already-decided task profile - a static table decided once (which is exactly what §3.2 already is) is the right-sized solution, not a trained classifier. Built `model_tiers.py`: a static `AGENT_TIER` map plus a `model_for(agent_name)` resolver, with `GRILLD_AI_MODEL` kept as a blanket override so the flat/cheap testing mode every phase has used still works unchanged when it's set.

**Doc curation and arbitrary user requests ("skip these docs," "add one for X," "I need this fast") have no design at all yet - correctly so, for now.** Raised as a genuine gap with a specific instinct attached: rather than Spring encoding every possible variation as an API flag, the Orchestrator itself (already an LLM) could interpret open-ended requests and route dynamically - closer to how Claude Code works than a settings panel. Checked against 2026 multi-agent orchestration research rather than taking that on faith: this is a real, named pattern ("Router / Dynamic Handoff," one of five dominant production topologies), and it comes with documented failure modes worth designing around deliberately - infinite handoff loops (no step "owns" the task), context degradation across handoffs, and non-deterministic routing (same input, different agent chains, materially harder to debug or cost-predict). None of the sources checked described an off-the-shelf fix for these - Grilld's own guardrails would need real design work, not just adopting the pattern. Recorded as its own Open Question in `product-and-architecture.md` §14, explicitly deferred to be planned as its own phase rather than bolted onto Phase 5's fixed roster after the fact.

**The escape hatch ("just generate it") was speced but never built - now it is.** `product-and-architecture.md` §7 already described a force-conclude mechanism; implementing it on the Python side just meant handling a new case in the Orchestrator's first instruction: if Spring says facts were left unresolved (because the user hit the escape hatch, or the interview's own "never trap the user" fallback fired), write every one of them into `/docs/ASSUMPTIONS.md` prominently, before delegating to anyone - not buried, not silently dropped. See the root `LEARNING.md` for the Spring-side half of this (the actual endpoint, and a real session-status bug it surfaced along the way).

---

## Phase 6 (in progress): live progress, not just an end-of-run summary

Every phase so far had Spring call Python and wait for one final answer. Phase 6's first job was making that live: the Run Report (`decisions-and-technical-architecture.md` §10.3) needs to show each specialist starting and finishing *as it happens*, not just a summary once all 10 are done 10 minutes later.

### Confirming the streaming API by actually calling it, not just reading about it

The docs already named the mechanism (§11.3: use the LangGraph server's own run/stream API, don't hand-build webhooks) but not the exact event shapes - and shapes matter here, since Spring has to parse them. Rather than guessing from documentation (which itself admitted gaps - "the exact SSE payload structure isn't documented here"), the actual `POST /threads/{id}/runs/stream` endpoint got called for real, with `stream_mode: updates` and `stream_subgraphs: true`, against a real (if deliberately minimal - one subagent, cheap) Orchestrator run.

What came back settled every open question in one shot:
- Every specialist delegation is a `task` tool call on the Orchestrator's own top-level `model` node - visible in that node's *normalized* `tool_calls` field (not the raw provider-specific `additional_kwargs`), with `subagent_type` and `description` right there.
- That same tool call's completion shows up in the top-level `tools` node's update, correlated by `tool_call_id` - and its `content` is literally the specialist's own final report (the narration, §10.2, for free).
- The **same** `tools` update also carries the cumulative `files` state at that exact moment - meaning a simple before/after diff tells you exactly which file(s) *that* specialist just wrote, without needing to peek inside its own internal steps at all.
- The namespaced `updates|tools:<id>` events (a specialist's *own* internal model/tool cycle, visible because `stream_subgraphs: true` was set) turned out to be unnecessary to consume - everything needed to track per-agent progress is already in the top-level stream.

This is worth remembering as a general instinct, not just what happened here: when a framework's docs are incomplete about an exact wire format, the fastest way to real answers is a live, minimal, deliberately cheap call against it - not more searching. The one live call (plus reusing its captured output as a permanent test fixture afterward, see the Java-side log) settled in minutes what documentation alone hadn't.

### A model that fails differently depending on what you ask it to do

Verifying this against Groq hit the exact same wall Phase 5 found for `market_analyst`'s Tavily calls - except this time it was the **Orchestrator itself** failing on its own `task` tool call, on the very first delegation, before any specialist even ran. Confirms the Phase 5 finding wasn't specific to search tools: it's Llama-3.3-70b-versatile's tool-calling reliability in general, and the Orchestrator relies on tool-calling (via `task`) just as much as any specialist does. Verification for this phase used Haiku instead - a small, deliberate, one-off cost to get a real answer, not a recurring one (the resulting transcript became a permanent zero-cost test fixture on the Java side).

### A process-management lesson, expensive in time if not money

Restarting `langgraph dev` repeatedly to test different model configs (`.env` changes require a restart, and `langgraph dev`'s reload-on-change hot-reload spawns a **new** worker process via Python's `multiprocessing` on every file-change) left several `spawn_main` worker processes orphaned across attempts - each still holding port 2024 from a since-dead parent, causing every subsequent "restart" to silently bind to nothing while an old, wrongly-configured instance kept answering requests. `--no-reload` looked like the obvious fix but broke something else instead: the Windows event-loop-policy fix in `__init__.py` only reliably applies in the child process reload mode spawns, not the bare parent process `--no-reload` runs everything in - so disabling reload traded one bug (orphaned workers) for a different one (`psycopg.InterfaceError` from the wrong Windows event loop). The actual fix was mundane: stay on default reload behavior, but check for *every* python.exe process system-wide (`Get-CimInstance Win32_Process`, not just `Get-NetTCPConnection`, which can report stale/cached listener info on Windows) before assuming a port is free.
