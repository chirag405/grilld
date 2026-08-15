# Grilld — Documentation

**Grilld** is an idea-to-blueprint multi-agent platform: a user arrives with a raw project idea, Grilld interrogates them through an adaptive, memory-backed interview (no fixed script — every question is generated from what they've actually said), calibrates everything to their real scale and skill level, then a swarm of specialist agents produces the complete starting package — architecture, infra, a phased delivery plan, and a curated `AGENTS.md` + skill-file kit their own coding agent uses to actually build it.

This folder is the canonical spec. It's pre-Phase-1 — no code has been written yet; these documents are the plan `docs/phases/phase-1/` will eventually be built against, per the project's phased-delivery process.

---

## Start here

| Doc | What it covers | Read this if you're asking... |
|---|---|---|
| **This file** | The 5-minute version of everything below | "What is this, roughly?" |
| [`product-and-architecture.md`](./product-and-architecture.md) | Positioning, scale calibration, the agent roster, orchestration flow, output package, MVP scope, pricing, risks | "What does it do, and what does a user get?" |
| [`interrogation-engine.md`](./interrogation-engine.md) | The dynamic slot-graph interview design — the actual product | "How does the interview itself work?" |
| [`decisions-and-technical-architecture.md`](./decisions-and-technical-architecture.md) | Every design decision made under `/grill-me` review, plus the full Python/Spring technical architecture | "Why was it built this way, and how exactly?" |

Read in that order for onboarding. For a specific question, jump straight to the relevant doc — each is self-contained enough to read alone.

---

## Product, in one paragraph

A chat session can't do this durably: state degrades as the context window fills, there's no adversarial quality check, and nothing keeps a dozen generated documents consistent with each other. Grilld fixes all three — canonical interview state lives in Postgres (not the conversation), a dedicated Rubric Agent scores and can reject an underspecified brief, and a Consistency Auditor checks the generated docs against each other. The interrogation is genuinely dynamic (no question bank — see `interrogation-engine.md`), opens by restating the idea back rather than asking a first question, and scales its own aggressiveness (a solo weekend hack gets a different interview than a funded team's MVP — see product-and-architecture.md §4, Scale Calibration). Full detail: [`product-and-architecture.md`](./product-and-architecture.md).

## Architecture, in one paragraph

Thirteen specialist agents plus an Orchestrator, flat hierarchy (specialists can't spawn subagents), human-in-the-loop only where explicitly allowed and never in parallel with another asking agent (to avoid a multi-agent question queue). Corrections and pivots go through a **blast-radius classifier**: a single-fact correction cascades cheaply and automatically, but anything invalidating a SEED slot or >~30% of the filled brief is surfaced explicitly and priced near a fresh run rather than silently reprocessed. Progress is shown via a **Run Report** — a single live document rewritten in place (Codex-plan-style), not a scrolling log, fed by a curated one-line `narration` on each agent's own structured output rather than a separate LLM call. Full detail: [`decisions-and-technical-architecture.md`](./decisions-and-technical-architecture.md) §10.

## Technical architecture, in one paragraph

Two services, one schema owner. **Spring Boot** owns auth, billing, and the entire canonical Postgres record (`project_briefs`, `slots`, `agent_executions`, `generation_runs`, credits) — it never delegates schema ownership, because atomic credit deduction and a single migration path both depend on that. **All agent/LLM logic lives in a separate Python service** built on **Deep Agents** (top-level orchestrator — `SubAgentMiddleware` for the specialist roster, `TodoListMiddleware` backing the Run Report, `HumanInTheLoopMiddleware` for asking agents, `FilesystemMiddleware` for doc-writing agents) with the **Interrogator built as its own LangGraph `StateGraph`**, registered as a Deep Agents subagent, since its control flow (vagueness triggers, laddering depth caps, contradiction detection) needs precise custom edges middleware doesn't give you. The two services talk over LangGraph's self-hostable server API (threads/runs/streaming) rather than fully hand-rolled webhooks. Full detail: [`decisions-and-technical-architecture.md`](./decisions-and-technical-architecture.md) §11.

## How it works, in one paragraph

There is no question bank. A **slot graph** tracks *what* must be known (seed slots present for every project, derived slots spawned by specific answers, probe slots spawned mid-conversation) while leaving *how to ask* entirely to the Interrogator in context. Every turn is a fresh, cheap LLM call assembled server-side from Postgres state — never an accumulating conversation — so turn 40 costs the same as turn 4. Vague answers ("scalable", "fast") are structurally rejected and forced to a concrete number. The interview opens by restating the idea back with 2-3 flagged inferences rather than asking a first question, because corrections are cheaper to give than answers are to compose. Full detail: [`interrogation-engine.md`](./interrogation-engine.md).

---

## Key decisions at a glance

The full rationale for each lives in [`decisions-and-technical-architecture.md`](./decisions-and-technical-architecture.md) §1–§11 — this is just the index:

1. Voice input is MVP-critical, not v2 — audio is never persisted (streamed to Deepgram, discarded after transcription)
2. Boilerplate code generation is deliberately v2, after demand is measured
3. "Audit Mode" (critique an existing repo) ships as v1.5 — larger market than greenfield, high code reuse
4. The interview opens with a restatement, not a question
5. The slot graph is shown to the user, expertise-gated into three views
6. Waiving is conservative — hard waives require an explicit quote, soft-deprioritized otherwise
7. Pivots are classified by blast radius and priced distinctly from minor corrections
8. Orchestration robustness is enumerated known-cases + a mandatory catch-all, never silent failure
9. AI logic runs in a separate Python service (Deep Agents + LangGraph), not in Spring Boot
10. *(§9 in the credit table)* Pricing is grounded in actual Aug 2026 Claude API pricing, re-verified rather than assumed
11. The Rubric Agent should use LangChain's `openevals` judge factory, and score categorically (`FAIL`/`BORDERLINE`/`PASS`) rather than on a noisy 1–5 scale

---

## What's deliberately not decided yet

- Where Grilld itself gets hosted (resolve at build step 1, not in the spec)
- A formal eval/regression harness for the interrogation engine (manual pass is enough at solo/pre-validation scale; LangSmith's dataset/experiment tooling is the answer when it's needed)
- A privacy/data-retention policy (deferred pre-launch; audio is already never persisted regardless)

These are tracked, not forgotten — see `decisions-and-technical-architecture.md` §10.7 for the full list and reasoning on each.
