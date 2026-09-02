# Grilld

**Turn a raw project idea into a real starting blueprint.**

Grilld interviews you about an idea through an adaptive, memory-backed conversation (no fixed question bank), calibrates the interview to how ambitious the project actually is, then hands it to a roster of specialist AI agents that each produce one piece of a complete project blueprint — market analysis, tech architecture, an infra plan, a phased roadmap, diagrams, and a curated `AGENTS.md` + skill-file kit your own coding agent can build from. You get it all back as a downloadable package of markdown files.

Full product and architecture documentation lives in [`docs/README.md`](./docs/README.md); a code-level tour of the implementation lives in [`ARCHITECTURE.md`](./ARCHITECTURE.md).

## How it works

1. **Interview** — a dynamic slot-graph interrogation tracks what's known about the project and what still needs answering; every question is generated fresh from context, not picked from a script.
2. **Calibration** — a Scale Calibrator agent sizes the interview and the eventual output to the project's real scope (a solo weekend hack gets a different treatment than a funded team's MVP).
3. **Generation** — ten specialist agents run sequentially against the finished brief (market/competition research, strategy, tech architecture, infra, diagrams, roadmap, skills, agent-file authoring, and a consistency audit), with live progress streamed to the frontend.
4. **Package** — the result is packaged into a zip of markdown docs (plus rendered Mermaid diagrams) that you own.

## Architecture

Three independently deployable services share one Postgres database, owned exclusively by the backend:

```
Browser ──HTTPS──▶ grilld-frontend  (Next.js, Vercel)
                         │  JSON + SSE
                         ▼
                   grilld-backend   (Spring Boot / Java — auth, billing,
                         │           canonical schema, orchestration API)
                         │  JSON + streaming
                         ▼
                   grilld-ai-service (Python — Deep Agents + LangGraph,
                                       all LLM/agent reasoning)
                         │
                         ▼
                   Anthropic Claude
```

| Service | Stack | Responsibility |
|---|---|---|
| [`grilld-backend`](./grilld-backend) | Java 26, Spring Boot, Postgres, Flyway | Google OAuth + JWT auth, the canonical data model, credits/billing (Lemon Squeezy), run orchestration, SSE progress, packaging |
| [`grilld-ai-service`](./grilld-ai-service) | Python, Deep Agents, LangGraph, Anthropic Claude, Tavily | The interview engine, scale calibration, and the ten-agent specialist roster |
| [`grilld-frontend`](./grilld-frontend) | Next.js 16, React 19, Tailwind | Interview UI, voice input, live run report, rendered diagrams, run history, billing |

See [`ARCHITECTURE.md`](./ARCHITECTURE.md) for the full annotated map (folder-by-folder, concept-by-concept) and [`docs/decisions-and-technical-architecture.md`](./docs/decisions-and-technical-architecture.md) for why it's split this way.

## Status

All planned backend and frontend phases are built and deployed — see [`docs/phases/`](./docs/phases) for the phase-by-phase build log (each phase folder has a `README`, `TESTING` checklist, and `SETUP` guide), and [`LEARNING.md`](./LEARNING.md) for the running engineering diary of decisions made along the way.

## Getting started locally

1. `docker compose up -d postgres` — starts the shared local Postgres instance.
2. Follow [`SETUP.md`](./SETUP.md) for every credential each service needs (Google OAuth, Anthropic, Tavily, LangSmith, Lemon Squeezy, etc.) and how to run all three services.
3. Each `docs/phases/phase-N/SETUP.md` covers the same ground incrementally, in case you want the credentials introduced one phase at a time.

## Documentation map

| Doc | Covers |
|---|---|
| [`docs/README.md`](./docs/README.md) | Product overview and doc index — start here for the *what* and *why* |
| [`docs/product-and-architecture.md`](./docs/product-and-architecture.md) | Positioning, scale calibration, agent roster, orchestration flow, MVP scope, pricing |
| [`docs/interrogation-engine.md`](./docs/interrogation-engine.md) | The dynamic slot-graph interview design |
| [`docs/decisions-and-technical-architecture.md`](./docs/decisions-and-technical-architecture.md) | Every design decision and the full technical architecture |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | Code-level tour of the implementation, folder by folder |
| [`SETUP.md`](./SETUP.md) | Consolidated deployment credentials and local run instructions |
| [`LEARNING.md`](./LEARNING.md) | The engineering diary — bugs hit, alternatives rejected, research done |
| [`docs/phases/`](./docs/phases) | Per-phase build docs (architecture, testing gate, setup) |

## Repo layout

```
/
├── docs/                 Product spec, decisions, and per-phase build docs
├── grilld-backend/       Spring Boot (Java) — auth, billing, canonical Postgres schema, API
├── grilld-ai-service/    Python (Deep Agents + LangGraph) — all agent/LLM logic
├── grilld-frontend/      Next.js UI
├── ARCHITECTURE.md       Annotated map of the whole codebase
├── SETUP.md              Consolidated deployment credentials
├── LEARNING.md           Engineering diary
└── docker-compose.yml    Local Postgres for development
```
