# Phase 3 — Python AI Service Foundation

The Python AI service exists, boots as a Deep Agents orchestrator, persists checkpoint state to Postgres, and is now genuinely called by Spring Boot over HTTP instead of `StubAiServiceClient`. The real Interrogator/Rubric Agent logic is Phase 4 - this phase is the plumbing.

## What this phase added

```
grilld-ai-service/
├── src/grilld_ai_service/
│   ├── __init__.py    — Windows asyncio event-loop fix (psycopg async + ProactorEventLoop don't mix)
│   ├── graph.py        — build_orchestrator(): the top-level Deep Agent, one trivial `ping` subagent
│   └── app.py           — get_graph(): wires AsyncPostgresSaver, referenced by langgraph.json
├── tests/
│   ├── unit_tests/                        — no live DB/API key needed
│   └── integration_tests/
│       ├── test_graph.py                   — real Claude call, proves subagent delegation
│       └── test_checkpoint_persistence.py   — proves checkpoint state survives a simulated restart
└── langgraph.json      — graphs: {"orchestrator": "grilld_ai_service.app:get_graph"}

grilld-backend/src/main/java/com/grilld/backend/aiservice/
└── HttpAiServiceClient.java   — @Profile("python-ai-service"), the real AiServiceClient
```

## Architecture

**Delegation, proven with a real Claude call:**

```
Orchestrator receives a message
  → calls task(subagent_type="ping", ...)          [SubAgentMiddleware]
  → ping subagent calls its echo tool
  → result flows back to the Orchestrator
  → Orchestrator reports the result
```

**Spring → Python, the real call path (HttpAiServiceClient):**

```
POST /api/v1/sessions (Spring)
  → HttpAiServiceClient.nextTurn(context)
      1. POST {langgraph}/threads {"thread_id": sessionId}   (409 on repeat = fine, not an error)
      2. POST {langgraph}/threads/{sessionId}/runs/wait      {assistant_id: "orchestrator", input: {...}}
      3. extract the last "ai" message, wrap as InterrogatorTurnResult.NextQuestion
  → SessionService persists the Turn as usual (unchanged from Phase 2)
```

Grilld's session id is used directly as the LangGraph thread id - no separate mapping table.

## Key files and what they're responsible for

| File | Responsibility |
|---|---|
| `graph.py` | The Orchestrator's structure - subagent roster (currently just `ping`), system prompt. No live DB dependency, so it's unit-testable with `checkpointer=None`. |
| `app.py` | The only place a live Postgres connection is constructed. Keeps `graph.py` clean. |
| `HttpAiServiceClient.java` | The Spring-side HTTP client. Deliberately minimal response mapping for Phase 3 - see "Known limitations" below. |

## Known limitations (deliberate, not oversights)

- **`HttpAiServiceClient`'s response mapping is a placeholder.** It forwards a plain message and wraps whatever Claude says back as a `next_question` with no fact extraction, no new slots, never concluding. The real `InterrogatorTurnResult` contract (extraction, slot spawning, technique selection) is built together with the real Interrogator in Phase 4 - the contract has to match on both ends at once, so building it partially now would just mean rebuilding it.
- **Checkpoint persistence is proven at the Python-SDK level, not yet through the actual server Spring calls.** `langgraph dev` (what Spring currently talks to) is deliberately ephemeral - its own thread/run bookkeeping is in-memory by design, separate from whatever checkpointer `app.py` constructs. The checkpointer code itself is proven correct (a direct-invocation test and an automated pytest both show state surviving a full simulated restart). Full production-grade persistence through the actual self-hosted server (`langgraph up`) needs more setup (its own Postgres schema, apparently a LangSmith license for full behavior) - deliberately deferred, consistent with `docs/product-and-architecture.md`'s existing "Grilld's own hosting is intentionally left undecided" stance. See `TESTING.md` for the precise breakdown of what's proven vs. deferred.
- **`StubAiServiceClient` is still the default.** `HttpAiServiceClient` only activates under the `python-ai-service` Spring profile - nothing changes for anyone not opting in.
