# Grilld

Idea-to-blueprint multi-agent platform. Full documentation lives in [`docs/README.md`](./docs/README.md) — start there.

## Layout

```
grilld-backend/      — Spring Boot (Java) platform: auth, billing, canonical Postgres schema, API
grilld-ai-service/   — Python (Deep Agents + LangGraph) AI service: all agent/LLM logic
grilld-frontend/     — Next.js UI (added at Phase 9, after the backend is complete end-to-end)
docs/                — product, architecture, technical architecture, decisions, phase docs
```

Currently in **Phase 1** — see `docs/phases/phase-1/` once it exists for that phase's setup and verification steps.
