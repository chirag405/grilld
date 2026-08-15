# Phase 4 — The Real Interrogator + the Rubric Agent

The interview is real now. `StubAiServiceClient`'s canned 3-turn script is replaced (under the `python-ai-service` profile) by a genuine LangGraph-driven interviewer that generates every question from what the person actually said, plus a separate adversarial Rubric Agent that has the final say on whether an interview is actually done.

## What this phase added

```
grilld-ai-service/
├── src/grilld_ai_service/
│   ├── interrogator/
│   │   ├── schemas.py     — Pydantic contract: ExtractedFact, NewSlot, WaivedSlot, NextQuestion, InterrogatorTurnResult
│   │   ├── vagueness.py   — detect_vagueness(): deterministic regex pre-check before the LLM ever sees the answer
│   │   └── graph.py       — 2-node StateGraph: check_vagueness -> generate_turn (structured Claude call)
│   └── rubric/
│       ├── schemas.py     — DimensionResult, RubricResult (FAIL/BORDERLINE/PASS, not 1-5)
│       ├── prompt.py       — the six-dimension rubric prompt (interrogation-engine.md §8)
│       └── graph.py        — 1-node stateless graph: openevals judge -> deterministic verdict computation
├── tests/
│   ├── unit_tests/
│   │   ├── test_vagueness.py         — 6 cases, no API key needed
│   │   └── test_rubric_verdict.py    — verdict/open_gaps computation, no API key needed
│   └── integration_tests/
│       ├── test_interrogator.py      — real Claude: opening restatement, vagueness -> CONCRETIZATION
│       └── test_rubric.py            — real Claude: thin brief rejected, well-covered brief accepted
└── langgraph.json   — graphs now: {"orchestrator", "interrogator", "rubric"}

grilld-backend/src/main/java/com/grilld/backend/
├── aiservice/
│   ├── RubricContext.java        — input shape for evaluateRubric (all slots, not just top-N open ones)
│   ├── RubricResult.java         — mirrors the Python rubric contract
│   └── HttpAiServiceClient.java  — rewritten: real InterrogatorTurnResult parsing + evaluateRubric via stateless /runs/wait
├── memory/WorkingContext.java    — +openGaps field, populated only right after a rubric rejection
├── slot/RubricEvaluation.java    — +getters, now actually written to
└── session/SessionService.java   — the gate: resolveConclusionAttempt()
```

## Architecture

**The Interrogator (grilld-ai-service, `interrogator` graph):**

```
Spring assembles WorkingContext fresh from Postgres every turn (unchanged since Phase 2)
  → POST {langgraph}/threads/{sessionId}/runs/wait  {assistant_id: "interrogator", input: {...}}
      check_vagueness (deterministic)  →  generate_turn (Claude, structured output)
  → turn_result: {extracted_facts, new_slots, waived_slots, next_question, ready_to_conclude}
  → Spring persists via SessionService.applyExtraction() (unchanged logic, real data now)
```

No thread-level memory needed - each turn is a complete, independent call. Grilld's session id doubles as the LangGraph thread id (only used for the theoretical multi-turn conversation state LangGraph's REST API expects; the Interrogator itself is stateless per invocation).

**The Rubric Agent (`rubric` graph) - the actual quality gate:**

The Interrogator's `ready_to_conclude=true` is a *proposal*, not a decision. `SessionService.resolveConclusionAttempt()` is what actually decides:

```
Interrogator says ready_to_conclude=true
  → SessionService.evaluateRubric(): assembles ALL slots (not just open ones) + the full brief JSON
  → POST {langgraph}/runs/wait  {assistant_id: "rubric", input: {...}}   (stateless - no thread)
      evaluate(): openevals judge scores 6 dimensions FAIL/BORDERLINE/PASS + reasoning
      _compute_verdict(): accept only if ALL SIX are PASS - computed in code, not asked of the LLM
  → RubricEvaluation persisted (one row per gate check, not per turn)
  → "accept"        → session actually concludes
  → "probe_further" → open_gaps fed into a fresh WorkingContext.openGaps, Interrogator called
                       again for ONE targeted follow-up (never trap the user - if the Interrogator
                       still can't produce one, accept anyway rather than loop)
```

**Why verdict is computed in code, not asked of the LLM:** initial testing had the judge return `probe_further` for a brief with zero FAILs and exactly one BORDERLINE - its own free-form verdict didn't consistently follow the stated threshold rule, an unreliable extra judgment layered on top of its own (reliable) per-dimension scores. `openevals`' job here is the categorical scoring primitive (`docs/decisions-and-technical-architecture.md` §11.4); deciding accept/reject from those scores is ordinary deterministic control flow, so it moved into `_compute_verdict()`.

## Key files and what they're responsible for

| File | Responsibility |
|---|---|
| `interrogator/graph.py` | `_build_prompt()` branches on: opening turn (restate-first), vagueness detected (force CONCRETIZATION), or a fresh rubric rejection (force a gap-targeted follow-up, explicitly forbid re-setting `ready_to_conclude`). |
| `interrogator/vagueness.py` | Deterministic word-boundary regex against a fixed vague-term list - cheap, no LLM call, runs before every `generate_turn`. |
| `rubric/graph.py` | `JudgeOutput` (what the LLM actually decides: scores only) vs `RubricResult` (the full contract Spring reads, verdict/open_gaps computed in `_compute_verdict`). |
| `SessionService.resolveConclusionAttempt()` | The gate itself - the one place "is this interview actually done" gets decided. |
| `SessionService.applyExtraction()` | Unchanged since Phase 2/3, but now processing real (not stubbed) extracted facts - contradiction detection was already proven against scripted data (Phase 4 work also added a live-fire NPE fix, see Known limitations). |

## Known limitations (deliberate, not oversights)

- **Interrogator laddering can get stuck repeating a line of questioning.** Live multi-turn testing this phase showed it fixating on "give me one specific abandoned project" across 4-5 consecutive turns despite `interrogation-engine.md` §9's "no same technique 3x consecutively" guardrail. That guardrail isn't yet enforced anywhere in code (Java or Python) - it's currently just a prompt instruction the model doesn't reliably follow. Not fixed this phase (it's a prompt/guardrail-enforcement problem, not a Rubric Agent problem); tracked here rather than silently accepted.
- **Rubric Agent gate is per-conclude-attempt, not periodic.** `interrogation-engine.md`'s `RubricService` was originally specced as "periodic coverage scoring" mid-interview. This phase implements it strictly as the gate on `ready_to_conclude` instead - cheaper (one extra LLM call only when actually trying to end, not every turn) and matches `product-and-architecture.md` §7's "scores the Project Brief... blocks progression until it passes" framing exactly. If telemetry later shows interviews concluding prematurely without ever triggering a rubric check, a periodic mid-interview pass can be added.
- **A malformed-output edge case was found and fixed live, not anticipated in design.** The Interrogator can return `next_question: null` while `ready_to_conclude: false` (the model just... didn't ask anything). `SessionService` now treats that the same as a conclude attempt (routes it through the rubric gate, which will reject it back to a real question unless the brief genuinely is done) rather than crashing with a `NullPointerException`. Caught via live multi-turn testing against the real service, not the mocked test suite - a concrete example of why `RubricGateTest`'s mocks alone weren't sufficient sign-off.
- **`StubAiServiceClient` still exists and stays the default profile.** Its `evaluateRubric()` always returns `accept` after 3 turns - it exercises the persistence pipeline, not rubric judgment quality.
