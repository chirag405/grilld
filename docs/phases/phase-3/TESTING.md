# Phase 3 — Testing (the gate for Phase 4)

Unlike Phase 1 (one clearly-scoped manual item) and Phase 2 (fully automated), Phase 3's gate has a genuinely nuanced item - checkpoint persistence is proven at one layer and honestly deferred at another. Read "What's proven vs. deferred" below before treating this as a simple checklist.

## Automated

- [x] `uv run pytest` (unit) — `build_orchestrator(checkpointer=None)` compiles, the `ping` subagent is configured, the system prompt is non-empty. No live DB or API key needed.
- [x] `uv run pytest tests/integration_tests/test_graph.py` — a **real** Claude call (Haiku) proves the Orchestrator delegates to the `ping` subagent, which calls its `echo` tool, and the result flows back correctly. Verified this session: inspected the full message trace, not just "some messages exist" - saw the actual `task(subagent_type="ping", ...)` tool call and the `echo` tool's response.
- [x] `uv run pytest tests/integration_tests/test_checkpoint_persistence.py` — two fully separate `AsyncPostgresSaver` connections and orchestrator objects, same thread id, second one correctly recalls a fact ("PINEAPPLE") only the first one was told. This is a genuine simulated-restart test, not a same-process memory check.

## Manual — verified this session

- [x] `./mvnw test` (Spring, unchanged) still passes with `HttpAiServiceClient` added - it's profile-gated (`python-ai-service`), so it doesn't affect the default test run.
- [x] **Full Spring → Python → Claude → Spring round trip**, driven for real: booted Spring with `SPRING_PROFILES_ACTIVE=local,python-ai-service`, got a real JWT via the Phase 1 Google login flow, called `POST /api/v1/sessions` with a real idea ("a tool for tracking home-brewed kombucha batches"), and got back a real, substantive, on-topic Claude response - persisted correctly to `discovery_sessions` and `turns`.
- [x] LangGraph server's REST API contract verified directly: `POST /threads {"thread_id": ...}`, `POST /threads/{id}/runs/wait` - confirmed a duplicate thread-creation call returns `409`, not a silent success, which `HttpAiServiceClient` specifically handles.

## What's proven vs. deferred (read this part)

**Proven:** the checkpointer code itself (`app.py`'s `AsyncPostgresSaver` wiring) is correct - state genuinely survives two fully independent connections/objects on the same thread, which is what "surviving a restart" means at the code level.

**Deferred, not proven:** whether the *actual server* Spring talks to (`langgraph dev`) persists across a real restart of that server process. It doesn't, by design - `langgraph dev`'s own thread/run bookkeeping is in-memory, separate from the checkpointer code above, meant for fast local iteration rather than durability. The production-shaped alternative, `langgraph up`, uses a completely different Go-based server with its own Postgres schema and appeared to require a LangSmith license for full operation when tested this session - real infrastructure work, deliberately not rushed into Phase 3. See `LEARNING.md`'s Phase 3 section for the full account of what was tried.

**What this means practically:** local development and Phase 4 work can proceed against `langgraph dev` without any issue - an interview session lost to a dev-server restart during active development is a minor annoyance, not a production problem, since there's no real user data at stake yet. Before Grilld has real users depending on session continuity, this needs a real decision: either get `langgraph up` properly licensed/configured, or find another self-hosting path that gives Postgres-backed persistence through the server layer itself. Tracked here rather than silently assumed solved.

## Sign-off

Phase 3's actual stated gate - "Spring calls the real Python service and gets a real (trivial) response; checkpointer state survives a service restart" - is met with one honest caveat: the checkpointer claim is proven at the code level, not yet through the specific server Spring calls today. Given `langgraph dev` is explicitly the *development* tool (not what would run in front of real users), this is an acceptable place to move on to Phase 4 from, with the caveat tracked above rather than forgotten.
