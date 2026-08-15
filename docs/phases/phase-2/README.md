# Phase 2 — Memory Layer

The interrogation's "memory": entities for sessions/slots/turns/briefs, the working-context assembler, and a stubbed connection to where the Python AI service will live — enough to exercise the full create-session → answer → next-question loop end to end before any real AI logic exists.

## What this phase added

```
grilld-backend/src/main/java/com/grilld/backend/
├── session/
│   ├── DiscoverySession.java, Turn.java, ExpertiseProfile.java   — entities
│   ├── DiscoverySessionRepository.java, TurnRepository.java
│   ├── SeedSlots.java              — the 8 universal slots, interrogation-engine.md §2
│   ├── SessionService.java          — orchestrates the per-turn loop
│   ├── SessionController.java       — POST /api/v1/sessions, POST /api/v1/sessions/{id}/answer
│   └── StartSessionRequest.java, SubmitAnswerRequest.java
├── slot/
│   ├── Slot.java, SlotWaive.java, RubricEvaluation.java          — entities
│   └── SlotRepository.java, SlotWaiveRepository.java, RubricEvaluationRepository.java
├── brief/
│   ├── ProjectBrief.java            — entity, @Version for optimistic locking
│   └── ProjectBriefRepository.java
├── memory/
│   ├── WorkingContext.java          — the per-turn payload shape
│   ├── WorkingContextAssembler.java — "the critical class" (product-and-architecture.md §8)
│   └── SlotPrioritizer.java         — importance x information_gain x blocking_factor ranking
├── aiservice/
│   ├── AiServiceClient.java         — the interface Spring calls through
│   ├── StubAiServiceClient.java     — canned responses; the only implementation until Phase 3
│   └── InterrogatorTurnResult.java  — mirrors interrogation-engine.md §3's structured-output contract
└── config/JacksonConfig.java        — explicit ObjectMapper bean (see note below)
```

## Architecture

**The per-turn loop** (interrogation-engine.md §3), as actually implemented:

```
SessionController.answer()
  → SessionService.submitAnswer()
      1. Turn.recordAnswer() on the pending turn
      2. WorkingContextAssembler.assemble() - fresh from Postgres:
           raw idea + compacted brief summary + last 3 turns + ranked open slots + answered topics
      3. AiServiceClient.nextTurn(context) - StubAiServiceClient today, real Python from Phase 3
      4. Apply the result:
           - extracted facts -> Slot.fill() + merge into ProjectBrief.briefJson
           - new slots -> Slot rows (skipped if the key already exists - defensive)
           - next question -> new Turn row
         or: readyToConclude -> session marked done, no new Turn
```

**The seam that makes Phase 3 low-risk:** `AiServiceClient` is the only thing `SessionService` depends on for AI behavior. Swapping the stub for a real Python-backed implementation (Phase 3) touches one new class; `SessionService`, `SessionController`, and every test written against them stays unchanged.

## Key files and what they're responsible for

| File | Responsibility |
|---|---|
| `WorkingContextAssembler.java` | Builds the exact payload the AI side receives - nothing accumulates between turns, rebuilt from Postgres every call |
| `SlotPrioritizer.java` | Ranks open slots so the (future) Interrogator's judgment is spent on phrasing, not triage - a Java-side responsibility per interrogation-engine.md §6 |
| `SessionService.java` | The only place that orchestrates a turn; owns the transaction boundary |
| `StubAiServiceClient.java` | Exercises every branch a real implementation must support: opening restatement, fact-extraction + follow-up, and conclude |

## Known limitations / deliberate scope cuts

- **No real Compactor.** `ProjectBrief.compactedSummary` exists as a column and is read by `WorkingContextAssembler`, but nothing writes to it yet - the brief never grows long enough during stub-driven testing to need it. Real compaction likely needs the Python side once it exists.
- **No credits/billing wiring yet.** Sessions and turns aren't gated on credit balance yet - that's explicitly deferred to Phase 7 per the approved implementation plan, not an oversight.
- **`ExpertiseProfile` and `RubricEvaluation` entities exist but nothing writes to them yet.** Real behavior for both depends on the actual Interrogator/Rubric Agent (Phase 4).

## A notable discovery this phase

Spring Boot 4 auto-configures a **Jackson 3** (`tools.jackson`) `ObjectMapper`, not the classic `com.fasterxml.jackson` one every tutorial assumes - a different Java type entirely, so `@Autowired ObjectMapper` (classic import) found no bean. `JacksonConfig.java` defines one explicitly using the classic type, which stays compatible with springdoc-openapi's own (transitive, classic-Jackson) dependency. See `LEARNING.md` for the full explanation.
