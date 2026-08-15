# Grilld — Idea-to-Blueprint Multi-Agent Platform (v2 Spec)

**One-liner:** User arrives with a raw project idea. Grilld grills them through an adaptive, memory-backed interview, calibrates everything to their actual scale and skill level, then a swarm of specialist agents produces the complete starting package — architecture diagram, phased delivery plan, infra scaffolding, business docs, and a curated set of `AGENTS.md` + skill files their own coding agent uses to actually build it.

**Positioning:** Not "AI writes your docs." It's *the interrogation that produces a blueprint your AI coding agent can execute against.*

---

## 1. Why This Isn't Replaceable by a Chat Session

Three architectural properties a single ChatGPT/Claude conversation structurally cannot provide:

**1. Durable, compaction-proof memory.** A chat session's state lives in the context window and degrades as it fills. Grilld's interview state lives in Postgres — every answer, every derived fact, every open contradiction, indexed and queryable. A user can walk away for three weeks and resume mid-interview with zero degradation. Industry consensus on agent memory is blunt about this: sessions are ephemeral, and anything that matters must live outside the conversation. Grilld's entire premise is built on that.

**2. Adversarial, rubric-scored questioning.** A chat assistant is agreeable — it takes your answer and moves on. Grilld has a dedicated **Rubric Agent** whose only job is to score the completeness of the Project Brief and *reject it* if it's underspecified, sending the Interrogator back for another round. That antagonistic internal loop is a system property, not a prompt.

**3. Cross-document consistency enforcement.** Twelve documents that must not contradict each other (the market doc's growth assumptions must match the infra doc's scaling plan must match the timeline's phase boundaries). This needs agents reading each other's outputs plus a dedicated consistency pass — not twelve independent prompts.

---

## 2. The Interrogation Engine (`/grill-me`)

This is the product. Everything downstream is a consequence of doing this well.

**Superseded:** the original design here was a fixed-round, question-bank interview. That design is replaced by a fully dynamic slot-graph engine — no rounds, no script, every question generated in the moment. See `interrogation-engine.md` for the complete, current design (slot graph, per-turn loop, question techniques, expertise profiling, termination, guardrails). The memory architecture (three layers: canonical state / working context / episodic log) and its compaction rule, described below, still apply unchanged under the dynamic design.

### 2.1 Memory architecture (three layers)

```
┌──────────────────────────────────────────────────────────────┐
│ LAYER 1 — CANONICAL STATE (Postgres, permanent)              │
│ project_brief JSONB: the structured, authoritative facts.    │
│ Every finalized answer updates this. Survives everything.     │
│ This is the single source of truth all downstream agents read.│
└──────────────────────────────────────────────────────────────┘
                          ▲ writes
┌──────────────────────────────────────────────────────────────┐
│ LAYER 2 — WORKING CONTEXT (per-turn, assembled in Java)      │
│ = compacted brief summary                                     │
│ + last N raw Q/A exchanges (N≈3, verbatim for tone/flow)      │
│ + open_gaps[] (what the Rubric Agent says is still missing)   │
│ + answered_topics[] (so it never repeats)                     │
│ Assembled fresh each turn. Never grows unbounded.             │
└──────────────────────────────────────────────────────────────┘
                          ▲ reads
┌──────────────────────────────────────────────────────────────┐
│ LAYER 3 — EPISODIC LOG (Postgres, append-only)               │
│ Full raw transcript. Never sent to the LLM wholesale.         │
│ Used for: audit trail, "why did you assume X?" explanations,  │
│ regeneration diffs, and user-facing session replay.           │
└──────────────────────────────────────────────────────────────┘
```

**The compaction rule:** when the brief summary exceeds a token budget, a **Compactor** step rewrites it — preserving all *decisions and constraints*, dropping *conversational texture*. Runs server-side in Spring, deterministically, at a threshold (~60% of the working-context budget) rather than waiting for degradation.

### 2.2 Question UX

Mix modalities to keep it fast without losing depth:

- **Quick-select chips** for enumerable answers (team size, timeline, budget bands) — one tap
- **Free text** for the questions where the *texture* of the answer matters (problem description, competitor take)
- **"I don't know / you decide"** always available → logged as an assumption, surfaced in `ASSUMPTIONS.md`
- **"Why are you asking?"** button on every question → Interrogator explains what the answer will change. Builds trust and improves answer quality.

(Superseded by `interrogation-engine.md` §10, which covers the same UX under the dynamic design plus voice input per `decisions-and-technical-architecture.md` §1.)

---

## 3. Agent Architecture

### 3.1 Design rules (borrowed from Claude Code's model)

- **Each agent has its own scoped tool list.** No agent gets tools it doesn't need — this is a correctness measure as much as a security one.
- **Flat hierarchy — subagents cannot spawn subagents.** The Orchestrator delegates; specialists execute and return. This mirrors Claude Code's deliberate constraint and keeps orchestration debuggable.
- **Specialists return only their final artifact**, not their working notes — the Orchestrator's context stays clean.
- **Serialize risky/dependent steps, parallelize independent ones.**
- **Human-in-the-loop is explicit**, not implicit: certain agents are permitted to interrupt and ask the user; most are not.
- **Any agent that can ask the user runs sequentially, never in parallel with another asking agent.** Tech Architect and Infra Agent are both "Yes" in §3.2 and sit on parallel branches in §3.3's diagram — but two agents interrupting at once has no good answer for "whose question does the user see first" without a question queue. Cheaper to just not build that problem: run Tech Architect to completion (including its questions) before starting Infra Agent, even though the diagram below draws them as a fan-out. Non-asking agents (Diagram, Roadmap, Skills Curator, etc.) still parallelize freely. See `decisions-and-technical-architecture.md` §10 for the full orchestration-robustness design (interrupt handling, narration, the Run Report).

### 3.2 Agent roster

| Agent | Tools | Reads | Produces | Can ask user? | Model |
|---|---|---|---|---|---|
| **Orchestrator** | delegate, read_state | Everything | Routing decisions, run plan | Only for approvals | Opus-tier |
| **Interrogator** | read_brief, write_answer, web_search (light) | Working context | Next question, brief updates | **Yes — its whole job** | Opus-tier |
| **Rubric Agent** | read_brief | Project Brief | Completeness score + `open_gaps[]` + accept/reject verdict | No | Sonnet |
| **Scale Calibrator** | read_brief | Project Brief | Scale tier + complexity ceiling (see §4) | No | Sonnet |
| **Market Analyst** | web_search, web_fetch | Brief | `MARKET_ANALYSIS.md` | No | Sonnet |
| **Competition Analyst** | web_search, web_fetch | Brief + market | `COMPETITION.md` | No | Sonnet |
| **Strategy Agent** | read_state | Brief + market + competition | `STRATEGY.md` | No | Sonnet |
| **Tech Architect** | web_search (version/lib currency) | Brief + scale tier | `TECH_STACK.md`, `ARCHITECTURE.md` | Yes — 1–2 targeted | Opus-tier |
| **Infra Agent** | web_search, file_write | Architecture + scale tier | `INFRA.md` + real config stubs | Yes — cloud/budget/deploy target | Opus-tier |
| **Diagram Agent** | file_write | Architecture | Mermaid + rendered SVG/PNG | No | Sonnet |
| **Roadmap Agent** | read_state | Everything + scale tier | `ROADMAP.md` — phased plan w/ timeline | No | Opus-tier |
| **Skills Curator** | read_state | Brief (skills) + tech stack | `SKILLS_NEEDED.md` + per-phase skill files | No | Sonnet |
| **Agent-File Writer** | file_write | Everything | `AGENTS.md`, `CLAUDE.md`, per-agent definitions | No | Opus-tier |
| **Consistency Auditor** | read_all_docs | All generated docs | Contradiction report → triggers targeted regeneration | No | Opus-tier |

### 3.3 Orchestration flow

```
                    ┌─────────────────┐
   Raw idea  ──────►│  Orchestrator   │
                    └────────┬────────┘
                             ▼
              ╔══════════════════════════════╗
              ║   INTERROGATION LOOP         ║
              ║                              ║
              ║  Interrogator ──► user       ║
              ║       ▲             │        ║
              ║       │             ▼        ║
              ║       │      Brief updated   ║
              ║       │             │        ║
              ║       │             ▼        ║
              ║       └──── Rubric Agent     ║
              ║          (reject → loop)     ║
              ╚══════════════╤═══════════════╝
                             │ accept
                             ▼
                    ┌─────────────────┐
                    │ Scale Calibrator│  ◄── determines complexity ceiling
                    └────────┬────────┘
                             ▼
        ┌────────────┬───────┴────────┬──────────────┐
        ▼            ▼                ▼              ▼
   Market       Tech Architect    (parallel fan-out stage)
   Analyst      (may ask user)
        │            │
        ▼            ▼
   Competition   Infra Agent (may ask user)
        │            │
        ▼            ▼
   Strategy     Diagram Agent
        │            │
        └─────┬──────┘
              ▼
        Roadmap Agent  ──►  Skills Curator
              │                   │
              └────────┬──────────┘
                       ▼
              Agent-File Writer
                       ▼
              Consistency Auditor ──(contradictions found)──┐
                       │                                     │
                       │◄────── targeted regeneration ───────┘
                       ▼
                 Final Package
```

---

## 4. Scale Calibration — The Thing That Makes Output Actually Useful

Generic AI project advice fails because it recommends the same architecture to a solo dev shipping in two weeks and a funded team building for 100k users. The **Scale Calibrator** assigns a tier, and that tier acts as a **hard complexity ceiling** on every downstream agent.

| Tier | Signals | Infra ceiling | Timeline granularity | Docs emphasis |
|---|---|---|---|---|
| **T0 — Weekend/Learning** | Solo, <1mo, no monetization, learning goal | Single container or plain host. No K8s, no CI beyond a test action. Managed everything. | Day-level, 1–2 phases | Heavy on learning path; skip market analysis |
| **T1 — Solo Indie / MVP** | Solo or pair, 1–3mo, pre-revenue, <1k users | Managed platform (Railway/Render/Fly) or single VPS. Docker + basic CI/CD. Managed DB. | Week-level, 3 phases | Full package, lean market analysis |
| **T2 — Small Team / Funded MVP** | 2–5 people, 3–6mo, revenue intent, 1k–50k users | Cloud-managed containers (ECS/Cloud Run) or light K8s. Real CI/CD, staging env, IaC basics, monitoring. | Sprint-level, 4–5 phases | Full package + GTM depth |
| **T3 — Scaling Product** | 5+ people, 6mo+, existing traction, 50k+ users | K8s/EKS + GitOps (ArgoCD), multi-env, observability stack, secrets mgmt, DR plan. | Quarter + sprint, 6+ phases | Full package + org/process docs |

**Enforcement:** the tier is injected into every specialist agent's system prompt as an explicit constraint, and the Consistency Auditor flags any doc that exceeds it. If the Infra Agent proposes Kubernetes for a T0 project, that's a caught bug, not a stylistic preference.

**The tier is user-visible and overridable** — shown as "We're building this as a T1 (Solo Indie MVP). Change?" Users often *want* to over-engineer for learning purposes, and that's a legitimate choice they should make consciously.

---

## 5. Output Package

```
/grilld-blueprint/
│
├── START_HERE.md              — how to use this package, in what order
├── README.md                  — the project's actual README (ready to commit)
│
├── /docs
│   ├── PROJECT_BRIEF.md        — the interrogation's distilled output
│   ├── ARCHITECTURE.md
│   ├── TECH_STACK.md           — choices + reasoning + alternatives rejected + why
│   ├── INFRA.md
│   ├── DATA_MODEL.md
│   ├── ROADMAP.md              — phased delivery plan w/ realistic timeline
│   ├── MARKET_ANALYSIS.md
│   ├── COMPETITION.md
│   ├── STRATEGY.md             — GTM, monetization, positioning
│   ├── RISKS.md                — technical + business risks, mitigations
│   └── ASSUMPTIONS.md          — every gap Grilld filled, flagged for correction
│
├── /diagrams
│   ├── architecture.mmd        — Mermaid source (editable)
│   ├── architecture.svg        — presentation-ready
│   ├── architecture.png
│   ├── data-flow.mmd
│   └── deployment.mmd
│
├── /agent-kit                  ◄── the differentiator
│   ├── AGENTS.md               — master context file for their coding agent
│   ├── CLAUDE.md               — Claude Code-specific (conventions, do/don't, gotchas)
│   ├── /agents
│   │   ├── backend-builder.md   — scoped subagent defs w/ YAML frontmatter,
│   │   ├── frontend-builder.md     tool lists, and role prompts — tailored to
│   │   ├── test-writer.md          THIS project's stack
│   │   └── infra-deployer.md
│   └── /skills
│       ├── phase-1-scaffold/SKILL.md    ◄── curated per phase (§6)
│       ├── phase-2-core-features/SKILL.md
│       └── phase-3-deploy/SKILL.md
│
└── /infra-stubs
    ├── Dockerfile
    ├── docker-compose.yml
    ├── .github/workflows/ci.yml
    └── (tier-appropriate: k8s manifests / terraform / nothing)
```

**`AGENTS.md` is the crown jewel.** It's written so the user drops it into Claude Code / Cursor and their agent inherits the *entire* interrogation's context — stack decisions and the reasoning behind them, conventions, what NOT to do, the phase they're currently in. It means they never re-explain their project to an AI again.

Written per current best practice: kept tight and focused on **things the agent would get wrong without it** — not restating what's inferable from the codebase.

---

## 6. Phased Delivery + Curated Skills

The Roadmap Agent produces phases; the Skills Curator produces a **skill pack per phase**. Critically: **skills are delivered progressively, not all at once** — the user gets Phase 1's skills at the start, unlocks Phase 2's when Phase 1 completes.

Why this matters: dumping 15 skill files on someone on day one is context pollution and decision paralysis. Three skills scoped to "get the scaffold up" is actionable.

**Example — T1 project, Spring Boot + Next.js:**

| Phase | Duration | Deliverable | Skills provided |
|---|---|---|---|
| **P1 — Scaffold** | Week 1 | Running skeleton, DB connected, auth stub, deployed to staging | `spring-boot-project-setup`, `postgres-schema-design`, `nextjs-app-router-scaffold` |
| **P2 — Core Loop** | Weeks 2–4 | The one feature that makes the product work, end to end | `spring-rest-api-conventions`, `jpa-entity-patterns`, `shadcn-form-patterns` |
| **P3 — Surrounding Features** | Weeks 5–7 | Supporting features, error handling, edge cases | `spring-exception-handling`, `integration-testing-testcontainers` |
| **P4 — Harden & Ship** | Week 8 | Monitoring, CI/CD, production deploy, docs | `github-actions-spring-pipeline`, `docker-multistage-java` |

Each `SKILL.md` follows the standard format (YAML frontmatter with name + description, markdown body with instructions) — so it drops straight into `.claude/skills/` and works.

**Progress tracking:** user marks a phase complete in Grilld → next phase's skills unlock → optionally, a short **check-in interview** ("did the scaffold go as planned? anything change?") that updates the brief and *regenerates downstream phases if needed*. This is the retention mechanic and the thing that makes Grilld a companion rather than a one-shot generator.

---

## 7. The Rubric Agent (Quality Gate)

Its job is to be **the adversary in the loop**. It scores the Project Brief against a rubric and blocks progression until it passes.

**Rubric dimensions** (each scored 1–5, with a required minimum):

| Dimension | Passing means |
|---|---|
| Problem clarity | A stranger could restate who this is for and what's broken |
| Scope boundedness | There's a clear "not doing this" list |
| Scale specificity | Concrete numbers, not "we'll see" |
| Technical constraints | Known integrations, known skills, known non-negotiables |
| Success definition | User can state what "working" looks like |
| Risk awareness | At least the top 2 risks are named |

**Output:** `{ scores: {...}, verdict: "accept" | "probe_further", open_gaps: [...] }`. On `probe_further`, `open_gaps[]` feeds straight into the Interrogator's next-turn context — so the follow-up questions are *targeted at the weakest dimension*, not random.

**Escape hatch:** user can force-accept after N rounds ("just generate it"). Everything unresolved lands in `ASSUMPTIONS.md`, prominently. Never trap the user in an interview loop.

**A second rubric** runs on generated docs — scoring specificity, tier-appropriateness, and actionability. Low-scoring docs get one regeneration attempt with the rubric feedback injected.

**Stricter bar for `AGENTS.md`/`CLAUDE.md` specifically:** these are the files a coding agent actually loads as context, and LLM-generated context files have been shown to *hurt* task success when low-quality (not just underperform — actively worse than no file at all). So the agent-kit output doesn't get the same one-shot-regen-and-ship treatment as other docs: if it fails the rubric twice, it's held back and flagged for manual review rather than delivered as-is. Everything else in the package can ship on a single regeneration attempt; the agent-kit cannot.

---

## 8. Tech Stack (building Grilld)

**Architecture: two services, one schema owner.** Spring Boot owns the platform (auth, billing, the canonical Postgres record, the API surface). All actual agent/LLM logic — the Interrogator, Rubric Agent, and every specialist — lives in a separate Python service built on **Deep Agents + LangGraph**. See `decisions-and-technical-architecture.md` §11 for the full design and the reasoning (framework layering, the service boundary, why Spring stays the sole schema owner, the async start-run/webhook/resume call pattern). This section covers what each side is responsible for.

### Backend — Spring Boot (Java): the platform, not the agents

Still a deliberate choice, now scoped to what Java is actually good at here — not agent orchestration, which moved to Python:

| Concern | Spring Boot fit |
|---|---|
| Interview session state | Enum-driven `status` field on `DiscoverySession` with `@Transactional` transitions |
| Canonical persistence | Spring Data JPA + Postgres (JSONB for the brief) — **sole owner** of `project_briefs`, `slots`, `agent_executions`, `generation_runs`, credits. The Python service never writes here directly. |
| Long-running generation | Spring Events + WebSocket/SSE to stream progress (the Run Report) to the frontend, fed by webhook callbacks from the Python service |
| Credits/billing | `@Transactional` credit deduction — atomicity matters when a run fails midway, and stays entirely inside Spring since it never has to coordinate with a second writer |
| Auth | Spring Security + JWT |
| AI service calls | Plain REST/HTTP client to the Python service's `start-run` / `resume-run` endpoints and its inbound webhook callbacks — **no Spring AI `ChatClient`, no direct Claude calls from Java.** Spring never talks to Claude; the Python service does. |

**Modules:**
```
grilld-api/          — REST controllers, WebSocket/SSE
grilld-run-tracker/  — receives Python webhooks, writes agent_executions,
                        rewrites run_report_md, drives resume-sweep (§10.5)
grilld-memory/       — brief store, working-context assembler (context sent
                        to Python per run, not agent execution itself)
grilld-packager/     — doc assembly, zip, diagram render
grilld-billing/      — credits, tiers, Lemon Squeezy webhooks
```

### AI Service — Python (Deep Agents + LangGraph)

```
grilld-ai-service/
├── orchestrator/       — Deep Agents top-level agent: SubAgentMiddleware
│                          delegates to the specialist roster, TodoListMiddleware
│                          backs phase/step tracking, HumanInTheLoopMiddleware
│                          handles the asking agents (Tech Architect, Infra)
├── interrogator/        — LangGraph StateGraph (slot graph, vagueness/laddering/
│                          contradiction logic), registered as a Deep Agents
│                          subagent — see decisions-and-technical-architecture.md §11
├── specialists/         — one module per specialist agent + prompt templates
├── checkpointer/        — LangGraph Postgres checkpointer config (own tables,
│                          same DB instance as Spring — durability for in-flight
│                          graph state and interrupts only, never business data)
└── webhooks/            — callbacks to Spring on step-complete / interrupt / done
```

LLM calls (LangChain's Anthropic integration) live entirely in this service. Spring Boot has no LLM client at all.

### Frontend — Next.js 15 + TS + Tailwind + shadcn/ui
- Chat-style interview UI with chip/free-text hybrid input
- Live "brief so far" side panel — user watches their project take shape as they answer (this is the magic-moment UX)
- **Run Report side canvas** (Codex/Claude-Code-plan-style) — a single live, read-only document that's rewritten in place as agents complete, not a scrolling log; per-agent status checklist plus a curated one-line narration per completed step. See `decisions-and-technical-architecture.md` §10.
- Mermaid live preview, doc browser, zip download

### Other
- **Postgres** — sessions, briefs, answers, packages, credits
- **S3/R2** — generated packages
- **Mermaid CLI** in a sidecar container for server-side SVG/PNG render
- **Lemon Squeezy** — billing (MoR, handles India→global tax)

---

## 9. Data Model

```sql
users(id, email, plan, credits_balance, created_at)

discovery_sessions(
  id, user_id, raw_idea, status, current_round,
  scale_tier, created_at, updated_at
)

interview_answers(
  id, session_id, round, question_text, answer_text,
  is_assumption, topic_key, asked_at
)

project_briefs(
  id, session_id, brief_json JSONB, compacted_summary TEXT,
  rubric_scores JSONB, finalized_at, version
)

generation_runs(
  id, brief_id, status, credits_charged, started_at, completed_at,
  run_report_md TEXT   -- the Run Report side canvas; rewritten in place by the
)                       -- Orchestrator after each agent_executions write, never
                        -- appended-to as a log. See decisions-and-technical-architecture.md §10.

agent_executions(
  id, run_id, agent_name, status, input_tokens, output_tokens,
  duration_ms, error, output_ref,
  narration TEXT,       -- curated 1-2 sentence summary from the agent's own
                         -- structured output (same pattern as `why_asking` on
                         -- the Interrogator) — not a separate LLM call, zero
                         -- extra cost. Source material for run_report_md.
  started_at, heartbeat_at   -- staleness detection for the resume sweep,
)                             -- decisions-and-technical-architecture.md §10.5
   -- observability + cost attribution per agent

platform_settings(
  key, value, updated_at
)  -- key-value: daily_spend_cap_usd, kill_switch_active, etc.
   -- cost circuit breaker, decisions-and-technical-architecture.md §10.6

packages(id, run_id, storage_url, status, created_at)

package_documents(id, package_id, doc_type, path, phase_number)

project_phases(
  id, package_id, phase_number, title, status[locked|active|complete],
  unlocked_at, completed_at
)

credit_transactions(
  id, user_id, delta, reason, run_id, created_at
)  -- audit trail; never mutate balance without a row here
```

`agent_executions` earns its place immediately — per-agent token cost is how you find out which agent is burning your margin.

---

## 10. Pricing & Credits

**Model:** credits, because runs vary wildly in cost (a T0 weekend project vs a T3 full package differ ~5x in tokens).

### Credit costs

| Action | Credits |
|---|---|
| Interview round | 1 |
| Rubric evaluation | free (bundled) |
| Core docs (README, Architecture, Tech Stack) | 10 |
| Market + Competition + Strategy | 15 |
| Infra package + config stubs | 12 |
| Diagram set | 5 |
| Agent kit (AGENTS.md + subagent defs + phase skills) | 15 |
| **Full blueprint (all of the above)** | **~50** |
| Phase check-in + regeneration | 8 |
| Single-doc regeneration | 3 |

### Tiers

| Tier | Price | Credits | Notes |
|---|---|---|---|
| **Free** | $0 | **60 credits on signup** (one free full blueprint) | No card required, but **signup requires Google OAuth login** — the anti-abuse gate against disposable-email farming of the free grant. This is the acquisition engine — they experience the whole thing once. |
| **Starter** | $12 one-time | 60 credits | For the second project |
| **Builder** | $19/mo | 150/mo, rollover up to 300 | Phase check-ins, regeneration, version history |
| **Pro** | $45/mo | 500/mo | Multi-project workspace, priority runs, export to Notion/Linear |
| **Team** | $99/mo | 1200/mo, 5 seats | Shared workspace, team brief templates |
| **Top-up** | $10 | 50 credits | Any tier |

**Why free credits > free tier limits:** giving a full 60 credits means the free user completes one *entire* blueprint and sees the actual value. A crippled free tier (docs but no infra, no agent kit) would show them the least differentiated part of the product.

**Margin check (superseded — see `decisions-and-technical-architecture.md` §9 for current numbers):** a full T2 blueprint is roughly 20–30 Claude API calls, several of them long-output. At current (Aug 2026) Claude 5 pricing this comes to roughly $1.3–1.5 in API cost before prompt caching, better than the $3–5 originally estimated here against older pricing. At 50 credits ≈ $8–16 of retail value, margin holds comfortably — 5–10x. Watch T3 runs specifically once it ships — cap max output tokens per agent and monitor `agent_executions` for outliers. `decisions-and-technical-architecture.md` §7 also adds a **Major Revision** credit tier (~35–40 credits) for pivots that invalidate most of the brief, priced near a fresh run rather than the cheap phase-check-in rate, so "revise anything, anytime" doesn't become a margin leak.

---

## 11. MVP Scope

**Ship these, cut everything else:**

1. Interrogator + memory layers + Rubric Agent (dynamic slot graph — see `interrogation-engine.md`), voice input included from day one
2. Scale Calibrator (T0–T2; skip T3 initially)
3. Tech Architect + Infra Agent + Diagram Agent
4. Roadmap Agent + Agent-File Writer (`AGENTS.md` + phase-1 skills only)
5. Consistency Auditor (contradiction detection, no auto-regeneration)
6. Package zip + download
7. Free credits + Lemon Squeezy top-up

**Explicitly cut from MVP:** Market/Competition/Strategy agents (most commoditized, least differentiating — add in v1.1), phase check-in loop, multi-project workspace, team seats, Notion export, T3 tier, boilerplate code generation.

**Deliberate MVP bet:** lead with the *engineering* side (interrogation → architecture → infra → agent kit). That's the defensible part and the part your own experience validates. Business docs are table stakes competitors will match.

---

## 12. Build Order

**Updated for the two-service split (decisions-and-technical-architecture.md §11).** Spring Boot and the Python AI service are separate build tracks that meet at the webhook/start-run contract — get that contract nailed down early (step 3) so the two sides can be built somewhat independently after.

1. Spring Boot skeleton, Postgres schema (full schema, including `agent_executions`/`generation_runs`/`platform_settings` up front, not incrementally), Spring Security + JWT
2. Memory layer (Spring side): brief store, working-context assembler, compactor — **build and test this before any agent logic, on either side**
3. Define and stub the Spring↔Python contract (`start-run`, `resume-run`, the three webhooks — §11.3) end-to-end with a fake Python service that just echoes back canned responses. This is the integration seam; nail it before investing in real agent logic on the Python side.
4. Python service skeleton: Deep Agents Orchestrator + `SubAgentMiddleware`, LangGraph checkpointer wired to Postgres, one trivial stub subagent — prove the start-run/webhook/resume-run loop works end to end for real, not against the fake from step 3
5. Interrogator as a LangGraph subgraph (slot graph, vagueness/laddering/contradiction logic) + Rubric Agent loop, registered as a Deep Agents subagent
6. Next.js interview UI (chips + free text + live brief panel, voice input)
7. Scale Calibrator + tier enforcement plumbed into specialist agent prompts
8. Tech Architect → Infra → Diagram agents (Python), `HumanInTheLoopMiddleware` wired for the asking agents
9. Roadmap + Skills Curator + Agent-File Writer (Python)
10. Consistency Auditor (Python)
11. Run Report side canvas (Spring: webhook-driven `run_report_md` assembly + SSE; frontend: live-diff rendering) — §10.3
12. Packager (zip, Mermaid render, S3)
13. Credits + billing + Lemon Squeezy webhooks, cost circuit breaker (§10.6)
14. `agent_executions` observability dashboard (for you, not users)

Step 2 before step 4/5 is the important sequencing call carried over from the original plan — if memory is wrong, every agent inherits the bug, regardless of which language it's written in. Step 3 is new and matters just as much: without a proven contract, steps 4 onward on the Python side and steps happening in parallel on the Spring side can drift out of sync.

---

## 13. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Interview feels like a tedious form | Chips over free text where possible; "why are you asking?" on every question; hard cap of ~20 questions with early-exit always available |
| Output is generic AI slop | Scale tier as a hard constraint + rubric scoring on generated docs + "alternatives rejected and why" sections force specificity |
| Cost blowout on big runs | Per-agent max_tokens caps, credit pre-authorization before run starts, `agent_executions` monitoring |
| User abandons mid-interview | Save on every answer; resumable by design; email nudge with "your brief is 60% complete" |
| Docs contradict each other | Consistency Auditor + single-source-of-truth brief that all agents read from |
| "I could just ask ChatGPT" | Lead marketing with the agent kit + phased skills — the artifacts a chat session can't produce |

---

## 14. Open Questions

- **Should the interview support voice input?** Talking through an idea is far more natural than typing it, and richer answers directly improve every downstream doc. Possibly the highest-leverage UX bet available — worth prototyping early.
- **Boilerplate code generation (actual starter repo) — v2 differentiator or scope creep?** It's the obvious next step and the obvious competitor target. Leaning toward: ship the blueprint first, watch whether users ask for code or are happy handing `AGENTS.md` to their own agent.
- **Should Grilld offer a "critique my existing project" mode?** Same interrogation engine, but pointed at a repo the user already has. Potentially a bigger market than greenfield, and reuses ~80% of the system.
- **How opinionated should Tech Architect be?** Strong opinions ("use Postgres, here's why") are more useful than balanced menus, but risk alienating users with existing preferences. Current lean: strongly opinionated recommendation + explicit "alternatives rejected and why" section.
