# Grilld — Architecture Guide

This is the map of the whole project, written for someone who doesn't already know Spring Boot, LangGraph, or Next.js. Each concept is explained once, in plain terms, then tied to the actual file in this repo that uses it - so you can read this document once and then recognize every piece when you open the code.

For the blow-by-blow story of *why* each piece was built the way it was (bugs hit, alternatives rejected, research done), read the three `LEARNING.md` files - this document is the map; those are the diary. For the original product spec, read `docs/product-and-architecture.md`, `docs/interrogation-engine.md`, and `docs/decisions-and-technical-architecture.md`.

---

## 1. What Grilld actually is

You type one or two sentences describing a project idea. Grilld interviews you about it (a handful of sharp questions, not a form), figures out how ambitious the idea actually is, then hands it to a roster of specialist AI agents that each write one piece of a real project blueprint - market analysis, tech architecture, an infra plan, a roadmap, diagrams, a starter agent kit. You get it all back as a zip of markdown files you own.

Three programs make this happen, each a separate deployable service:

```
                    ┌─────────────────────┐
   Browser  ───────▶│  grilld-frontend      │  Next.js - the website
                    │  (Vercel)             │
                    └──────────┬───────────┘
                               │ HTTPS, JSON + SSE
                               ▼
                    ┌─────────────────────┐
                    │  grilld-backend       │  Spring Boot (Java) - the
                    │  (Railway)             │  only thing that talks to
                    └──────────┬───────────┘  the database or handles money
                               │ HTTPS, JSON + streaming
                               ▼
                    ┌─────────────────────┐
                    │  grilld-ai-service    │  Python + LangGraph/Deep Agents
                    │  (Railway)             │  - all the actual AI reasoning
                    └─────────────────────┘
                               │
                               ▼
                    Anthropic Claude (the LLM)
```

**Why three separate programs instead of one?** Two different concerns, two different natural languages for them: Java/Spring Boot is a mature, boring, very safe choice for "hold the database, handle money, enforce who's allowed to do what" - the parts where a bug is expensive. Python is the natural home for "orchestrate a bunch of AI agents that call an LLM, use tools, and sometimes need a human to jump in mid-task" - that whole ecosystem (LangGraph, Deep Agents) is Python-first. Splitting them means each side can be simple and idiomatic in its own language instead of one codebase awkwardly doing both jobs. The frontend is separate because that's just how modern websites are built (a Next.js app is its own deployable thing, talking to APIs over HTTP) - Vercel hosts it, Railway hosts the two backend services.

Postgres (one shared database) sits behind `grilld-backend` only - the Python service never touches it directly except through its own separate "checkpointer" tables (explained in §4), which store *conversation memory for the AI graphs*, not Grilld's business data (users, credits, sessions).

---

## 2. Folder structure, annotated

```
/
├── docs/                          Product spec, decisions, and per-phase build docs
│   ├── product-and-architecture.md    The original product spec - what to build
│   ├── interrogation-engine.md        Deep-dive spec on how the AI interview works
│   ├── decisions-and-technical-architecture.md   Technical decisions and why
│   └── phases/phase-N/                One folder per build phase: README (what got
│                                       built), TESTING (the verification checklist),
│                                       SETUP (credentials/env needed)
│
├── LEARNING.md                    The Spring Boot / Java side's running diary
├── ARCHITECTURE.md                This file
├── SETUP.md                       How to get everything running locally
├── docker-compose.yml             Starts a local Postgres for development
│
├── grilld-backend/                 Java, Spring Boot - see §3
│   └── src/main/java/com/grilld/backend/
│       ├── auth/                       Google login, JWT issuing/validation
│       ├── user/                       The User entity, profile, /me endpoint
│       ├── session/                    The interview: DiscoverySession, Turn, SessionService
│       ├── slot/                       Slot = one fact the interview needs to fill in
│       ├── brief/                      ProjectBrief = the assembled facts, as JSON
│       ├── memory/                     Builds the payload sent to the Python side each turn
│       ├── aiservice/                  The HTTP client that calls grilld-ai-service
│       ├── generation/                 Triggering/tracking a blueprint run, Run Report,
│       │                               packaging the result into a zip, cost circuit breaker
│       ├── billing/                    Credits, Lemon Squeezy checkout/webhooks
│       ├── voice/                      Speech-to-text seam (Phase 12 - see §7)
│       ├── common/                     Cross-cutting: error handling, rate limiting
│       ├── config/                     Small Spring configuration beans
│       └── tools/                      One-off command-line utilities (e.g. JWT key generator)
│
├── grilld-ai-service/               Python, LangGraph/Deep Agents - see §4
│   └── src/grilld_ai_service/
│       ├── app.py                       The entry point the LangGraph server calls
│       ├── graph.py                     The Orchestrator - delegates to every specialist
│       ├── interrogator/                The interview-question-asking subgraph
│       ├── rubric/                      Judges whether the interview has gathered enough
│       ├── scale_calibrator/            Decides how ambitious the project is (T0-T3)
│       └── specialists/                 One file per specialist agent (market, tech, etc.)
│
└── grilld-frontend/                  Next.js (React) - see §5
    └── src/
        ├── app/                          Pages, routes (App Router - folders = URLs)
        │   ├── page.tsx                     Landing page
        │   ├── interview/page.tsx           The interview screen (the whole app, really)
        │   ├── billing/page.tsx             Buy credits
        │   ├── auth/                        Login callback, sign-out
        │   └── api/proxy/[...path]/         The one route that forwards browser calls to Spring
        ├── components/                   React components (mostly UI)
        │   └── ui/                           Vendored/adapted component-library pieces
        └── lib/                          Shared code: API clients, TypeScript types
```

---

## 3. The backend (`grilld-backend`) - Spring Boot concepts, explained

Spring Boot is a Java framework: you write small, focused pieces and annotate them (`@Something` above a class), and Spring wires everything together and runs the actual web server for you. Here's every concept you'll see, explained once:

- **Controller** (`@RestController`) - a class whose methods handle incoming HTTP requests. `@GetMapping("/balance")` on a method inside a class annotated `@RequestMapping("/api/v1/billing")` means "handle `GET /api/v1/billing/balance`." A controller's job is thin: read the request, call a service, return the result - it should never contain business logic itself.
- **Service** (`@Service`) - a plain class holding the actual business logic, called by controllers. `GenerationService`, `CreditService`, `SessionService` are all services.
- **Repository** (`interface ... extends JpaRepository<Entity, IdType>`) - you write an empty interface, and Spring Data JPA generates a real database-querying implementation for you at startup, purely from the method's name (`findByUserIdOrderByUpdatedAtDesc` becomes a real SQL query, no SQL written by hand).
- **Entity** (`@Entity`) - a plain Java class that represents one row of a database table. `User`, `DiscoverySession`, `GenerationRun` are entities. Hibernate (the library underneath Spring Data JPA) turns reading/writing these objects into real SQL behind the scenes.
- **Migration** (`src/main/resources/db/migration/V1__init_schema.sql`, `V2__...`, etc.) - plain SQL files, run once each, in order, by a tool called Flyway, every time the app starts. This is how the database's structure (which tables/columns exist) gets created and changed over time, identically on every developer's machine and every real deployment. Once a migration has run, it's never edited - a schema change is always a *new* numbered file.
- **Dependency injection** - when a class's constructor asks for another class as a parameter (e.g. `GenerationController(GenerationService generationService)`), Spring automatically finds (or creates) an instance of that class and hands it in. You never write `new GenerationService(...)` yourself; Spring's "container" holds one instance of each service/repository and passes them around wherever they're asked for.
- **DTO / record** - a `record` (Java's built-in immutable data-holder syntax) used purely to shape a JSON request/response, distinct from an `@Entity` (which shapes a database row). `GenerationRunResult`, `TranscriptionResult` are records - what a controller method actually returns, which Spring automatically serializes to JSON.
- **`@Value("${some.property:default}")`** - injects a configuration value from `application.properties` (or an environment variable) into a field/constructor parameter. `${DB_URL}` with no default means "fail to start if this isn't set"; `${FRONTEND_BASE_URL:http://localhost:3000}` means "use this default if unset."

### The request/security pipeline

Every HTTP request to `grilld-backend` passes through a chain of filters before it reaches a controller (`SecurityConfig.java` configures this chain):

1. **CORS check** - is the request's origin (the frontend's domain) allowed to call this API at all? Configured once, allowing exactly `grilld.frontend.base-url`.
2. **JWT validation** (`oauth2ResourceServer`) - if the request carries an `Authorization: Bearer <token>` header, Spring verifies it was really signed by this server (`JwtConfig` holds the signing key) and hasn't expired. If valid, the request is "authenticated" and the token's `subject` claim (the user's UUID) becomes available to controllers via `@AuthenticationPrincipal Jwt jwt`.
3. **Authorization check** (inside `SecurityConfig`'s `authorizeHttpRequests` block) - a short allowlist of paths that don't need a valid token at all (health checks, the OAuth2 login flow, Lemon Squeezy's webhook); everything else requires the token from step 2 to have been valid, or the request is rejected with 401 before any controller code runs.
4. **Rate limiting** (`RateLimitInterceptor`, wired per-endpoint-group in `RateLimitConfig`) - a per-user token-bucket limit on the specific endpoints that cost real money to call (answering an interview question, starting a generation run, checking billing) - independent from step 2/3, this doesn't ask "are you logged in," it asks "are you calling this too often."
5. Finally, the matched **controller** method runs.

**Login** works differently from every other request, because it's the one moment there's no JWT yet: the browser is redirected to Google, Google redirects back with proof of identity, `OAuth2LoginSuccessHandler` runs (finds-or-creates the `User` row, then calls `TokenService` to mint a brand new JWT), and redirects the browser to the frontend with that token. Every request after that carries the token.

**Errors** all funnel through one place: `GlobalExceptionHandler` (`@RestControllerAdvice`) catches every exception type thrown anywhere in a controller/service and turns it into the same JSON shape (`ErrorResponse`) with the right HTTP status code - `ResourceNotFoundException`→404, `InsufficientCreditsException`→402, `AccessDeniedException`→403, `GenerationBlockedException`/`AiServiceUnavailableException`/`TranscriptionUnavailableException`→503, anything unexpected→500. This is why the frontend can trust every error response has the same `{status, error, message, path}` shape no matter what went wrong.

### Backend package tour

| Package | What it owns |
|---|---|
| `auth` | Google OAuth2 login wiring, JWT signing/validation (`JwtConfig`, `TokenService`) |
| `user` | The `User` entity (email, name, picture, credits balance, plan), `/api/v1/me` |
| `session` | The interview itself: `DiscoverySession` (one interview), `Turn` (one question+answer), `SessionService` (the only class that orchestrates a turn) |
| `slot` | A `Slot` is one fact the brief still needs (e.g. "who is this for") - `OPEN`/`FILLED`/`ASSUMED`/`WAIVED`/`BLOCKED` |
| `brief` | `ProjectBrief` - the slots' values assembled into one JSON document, this is what generation actually reads |
| `memory` | `WorkingContextAssembler`/`SlotPrioritizer` - build the exact payload sent to the Python Interrogator each turn, rebuilt fresh from Postgres every time (nothing accumulates in memory between requests) |
| `aiservice` | `AiServiceClient` (interface) / `HttpAiServiceClient` (real implementation) - the one place that calls `grilld-ai-service` over HTTP |
| `generation` | Triggering a full blueprint run (`GenerationService`), tracking its live progress (`RunReportService`, `RunReportController`'s SSE stream), the cost circuit breaker, packaging the result into a downloadable zip (`PackagerService`, `PackageController`), previewing one generated document (`GeneratedDocumentController`) |
| `billing` | `CreditService` (the *only* code allowed to change a user's credit balance), Lemon Squeezy checkout URL + webhook handling |
| `voice` | Speech-to-text seam - see §7 |
| `common` | `GlobalExceptionHandler`, the rate limiter |

### Every API endpoint, at a glance

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/me` | The logged-in user's own profile + credit balance |
| POST | `/api/v1/sessions` | Start a new interview from a raw idea |
| GET | `/api/v1/sessions` | List this user's past sessions (for the history screen) |
| GET | `/api/v1/sessions/{id}` | One session's full detail (brief, slots, status) |
| GET | `/api/v1/sessions/{id}/turns` | The full question/answer transcript for one session |
| POST | `/api/v1/sessions/{id}/answer` | Submit an answer, get the next question (or conclude) |
| POST | `/api/v1/sessions/{id}/scale-tier` | Calibrate how ambitious the project is (T0-T3) |
| PUT | `/api/v1/sessions/{id}/scale-tier` | Manually override the calibrated tier |
| POST | `/api/v1/sessions/{id}/force-conclude` | Skip straight to "ready for generation" |
| PUT | `/api/v1/sessions/{id}/slots/{slotKey}` | Edit one already-filled answer directly |
| POST | `/api/v1/sessions/{id}/generate` | Charge credits, kick off a full blueprint run |
| GET | `/api/v1/sessions/{id}/runs` | List every generation attempt for this session |
| GET | `/api/v1/sessions/{id}/runs/{runId}/report` | Poll the Run Report (progress) once |
| GET | `/api/v1/sessions/{id}/runs/{runId}/events` | The same Run Report, as a live SSE stream |
| GET | `/api/v1/sessions/{id}/runs/{runId}/documents?path=...` | Read one generated document's content |
| GET | `/api/v1/sessions/{id}/runs/{runId}/package` | Is the zip ready? What's in it? |
| GET | `/api/v1/sessions/{id}/runs/{runId}/package/download` | Download the actual zip |
| GET | `/api/v1/billing/balance` | Credit balance + recent transactions |
| GET | `/api/v1/billing/checkout-url` | A Lemon Squeezy hosted checkout link |
| POST | `/api/v1/billing/webhooks/lemonsqueezy` | Lemon Squeezy calls this after a real purchase (no JWT - HMAC-signed instead) |
| POST | `/api/v1/voice/transcribe` | Upload a recorded answer clip, get text back |

---

## 4. The AI service (`grilld-ai-service`) - LangGraph/Deep Agents concepts

This is Python. The mental model is different from Spring Boot's "controllers and services" - here, almost everything is a **graph**.

- **Graph** - a small state machine: a set of **nodes** (steps) connected by **edges** (which step runs next), operating on one shared **state** object that gets passed from node to node and updated along the way. `interrogator/graph.py` is a graph; so is the top-level Orchestrator in `graph.py`.
- **State** - a typed dictionary describing everything the graph needs to remember while it runs (the conversation so far, extracted facts, whatever). Defined once per graph (see `interrogator/schemas.py`), and every node either reads from it or returns a partial update to merge into it.
- **Agent / subagent** - in Deep Agents, an "agent" is really just a system prompt + a name + a list of tools it's allowed to call, handed to a framework that knows how to run an LLM in a loop calling tools until it's done. Every file in `specialists/` (market, tech, diagram, audit, delivery) is one of these - not custom control-flow code, just a declarative definition.
- **Orchestrator** (`graph.py`) - the top-level agent that *delegates* to every specialist, in a specific order, rather than doing any of the writing itself. Its system prompt encodes the delegation order.
- **Subgraph** - a graph nested inside a bigger graph, treated as a single node from the outside. The Interrogator (`interrogator/graph.py`) is a subgraph: from the Orchestrator's point of view, "run the interview" is one step, even though internally it loops through many question/answer turns.
- **Checkpointer** - LangGraph's built-in mechanism for persisting a graph's state to a real database (Postgres, here) after every step, keyed by a `thread_id`. This is what makes a conversation survive a server restart: if the process dies mid-interview, the next call with the same `thread_id` picks up exactly where it left off. Grilld uses the session's own UUID as the `thread_id` directly - no separate mapping table needed.
- **Streaming** - rather than waiting for an entire multi-minute generation run to finish before responding, the Python service streams progress events back as each specialist starts/finishes (Server-Sent Events under the hood), which `grilld-backend`'s `HttpAiServiceClient` reads and turns into `AgentExecution` row updates in real time - that's what powers the live Run Report.
- **Rubric Agent** (`rubric/graph.py`) - not a conversational agent, just an LLM call that scores whether the interview has gathered enough real substance (FAIL/BORDERLINE/PASS) - the gate that decides whether "I think we're done" from the user actually ends the interview.
- **Scale Calibrator** (`scale_calibrator/graph.py`) - a similar one-shot LLM judgment: given the filled brief, decide how ambitious this project is (T0 = weekend hack, up to T3), which tunes how deep the specialists go.

### What actually happens on one interview turn

1. Frontend calls `POST /api/v1/sessions/{id}/answer` on Spring.
2. Spring's `SessionService` saves the raw answer, asks `WorkingContextAssembler` to rebuild the current state (open slots, recent turns, expertise so far) fresh from Postgres, and calls `AiServiceClient.nextTurn(...)`.
3. `HttpAiServiceClient` makes a real HTTP call into `grilld-ai-service`, which runs the Interrogator subgraph for exactly one turn: classify the user's intent (answer/question/correction/skip/finish/unrelated - by asking the LLM to judge, never by matching keywords), extract any facts, decide the next question (and how to ask it - text/number/chips/voice), and possibly recommend concluding.
4. The Python response comes back as a structured JSON contract (`InterrogatorTurnResult`), which Spring parses, persists (new/updated slots, a new `Turn` row), and returns to the frontend as the next question to show.

---

## 5. The frontend (`grilld-frontend`) - Next.js concepts

Next.js's **App Router**: a folder under `src/app/` becomes a URL. `src/app/interview/page.tsx` is the page at `/interview`. A file named `route.ts` inside such a folder is a **Route Handler** - a small serverless API endpoint, not a page - `src/app/api/proxy/[...path]/route.ts` is one (the `[...path]` folder name means it matches any path after `/api/proxy/`).

**Server Components vs. Client Components** - by default, every component in the App Router runs only on the server, rendering to HTML before it ever reaches the browser (no `"use client"` needed, no JavaScript shipped for it). A file starting with `"use client"` opts into being a normal interactive React component that also runs in the browser (needed for anything with `useState`, `onClick`, etc.). Almost everything in `src/components/` is a Client Component, because almost everything in this app is interactive.

**Why there's a proxy route at all.** The JWT that Spring issues after login gets stored in an **httpOnly cookie** (`src/app/auth/callback/route.ts` does this) - meaning page JavaScript can never read it, which is a deliberate security choice (a bug or a malicious script on the page can't steal the token). But that means a Client Component's own `fetch()` call can't attach an `Authorization` header itself, since it can't read the cookie either. `src/app/api/proxy/[...path]/route.ts` solves this: it's a Route Handler, which *does* run on the server and *can* read the cookie, so every Client Component call goes `browser → /api/proxy/... → (attach real JWT) → grilld-backend` instead of straight to Spring. `src/lib/api-client.ts`'s `apiClient()` function is the one place that builds this call.

**Frontend package tour**:

| Path | What it's for |
|---|---|
| `app/page.tsx` | Landing page - pitch, pricing, the agent-pipeline diagram |
| `app/interview/page.tsx` | The whole interview experience: idea input, chat-style Q&A, live brief, session history |
| `app/billing/page.tsx` | Buy credit packages |
| `app/auth/callback/route.ts` | Receives the JWT after Google login, stores it as an httpOnly cookie |
| `app/api/proxy/[...path]/route.ts` | Forwards every authenticated browser call to Spring with the real JWT attached |
| `components/AnswerForm.tsx` | Renders the right input control (text/number/chips/voice) for whatever the Interrogator asked for |
| `components/GenerationPanel.tsx` | Calibrate → generate → watch the Run Report live → download the zip |
| `components/VoiceRecorder.tsx` | Records a spoken answer and sends it for transcription |
| `components/ui/*` | Adapted pieces from curated component libraries (prompt-kit, Watermelon UI, etc.) - see `frontend-component-kits` skill |
| `lib/types.ts` | TypeScript types mirroring Spring's JSON response shapes field-for-field |
| `lib/api-client.ts` | The one function Client Components use to call the backend (via the proxy) |

---

## 6. A feature tour, end to end

- **Sign in** - Google OAuth2 login, issued as a 30-day JWT in an httpOnly cookie. Profile (name, picture) is pulled from Google on every login.
- **Start an idea** - one or two sentences, no form.
- **The interview** - a chat-style back-and-forth. Every answer is semantically classified (never keyword-matched) as an actual answer, a question back, a correction to something said earlier, a request to skip, or a request to finish. Chip-style multiple-choice questions, free text, numeric input, and (Phase 12, plumbing only so far) voice input are all supported per-question, decided by the AI, not hardcoded per question type.
- **Live brief panel** - the facts extracted so far, editable directly.
- **Reasoning trace** - a user-safe "what I just decided and why" summary shown after each turn, backed by a real persisted column, not an afterthought.
- **Scale calibration** - before generation, the system decides how big a blueprint this idea deserves.
- **Generation** - ten specialist agents (market, tech, infra, diagram, roadmap, skills, agent-file writer, consistency auditor, delivery, and the orchestrator itself) run in sequence, each producing one or more markdown documents, watched live via the Run Report (progress bar, per-agent status, a live-updating summary document, and now - Phase 12 - a click-to-preview of any completed document, with any Mermaid diagram inside it actually rendered as a diagram, not shown as raw text).
- **Package download** - everything zipped up, one download.
- **Session history** - every past session, resumable; reopening one that already generated a blueprint now (Phase 12) restores the Run Report and download link instead of showing a blank panel.
- **Billing** - no free tier; every credit is purchased via Lemon Squeezy (one-time packages, not a subscription). Answering interview questions is always free; only the 50-credit generation step is gated, via a persistent "you're out of credits" modal with a link to billing rather than blocking the user from typing.

---

## 7. Phase 12 (the most recent work) in this map

Three things landed together, all documented in full in `docs/phases/phase-12/README.md`:

1. **Voice input plumbing** - `voice/TranscriptionService` is an interface with one real implementation today, `UnconfiguredTranscriptionService`, which honestly fails every request until a real speech-to-text provider is chosen and wired in (same "interface + swappable implementation" pattern as `AiServiceClient` and `PackageStorage`). The frontend's `VoiceRecorder.tsx` records real audio and uploads it already - only the actual transcription call is stubbed.
2. **Rendered Mermaid diagrams** - `components/ui/mermaid-diagram.tsx` turns fenced ` ```mermaid ` blocks into real rendered diagrams anywhere markdown is shown in the app (chat, Run Report, the new document preview).
3. **Run history actually restores a finished blueprint** - a new `GET /api/v1/sessions/{id}/runs` endpoint plus a `GenerationPanel` that checks for a session's past runs on mount, so reopening an old, already-generated session shows its Run Report and download link again instead of an empty panel.

---

## 8. Where to go next

- Want the phase-by-phase build story, including every bug hit and why each design choice was made? Read `LEARNING.md` (backend), `grilld-frontend/LEARNING.md` (frontend), `grilld-ai-service/LEARNING.md` (AI service), top to bottom, in that order.
- Want to run it locally? `SETUP.md` at the repo root.
- Want the original product thinking? `docs/product-and-architecture.md` first, then the two deeper-dive docs alongside it.
- Want to know what's still missing or deliberately deferred? Every `docs/phases/phase-N/README.md` has a "Known limitations" or "Deliberate boundaries" section - these are honest, maintained lists, not stale TODOs.
