package com.grilld.backend.aiservice;

import com.grilld.backend.memory.WorkingContext;

/**
 * The seam between Spring and the Python AI service. One method, because from
 * Spring's side a turn is a turn - the same call shape works whether it's the
 * very first turn (context has no prior turns, so the Interrogator restates
 * the idea per docs/decisions-and-technical-architecture.md §4) or turn 40.
 *
 * StubAiServiceClient is the only implementation until Phase 3, when a real
 * implementation calls the LangGraph server API
 * (docs/decisions-and-technical-architecture.md §11.3). Nothing that calls
 * this interface needs to change when that happens.
 */
public interface AiServiceClient {

    InterrogatorTurnResult nextTurn(WorkingContext context);

    /**
     * The quality gate (docs/product-and-architecture.md §7). Called only
     * when the Interrogator itself signals readyToConclude - the antagonistic
     * check on that signal, not a per-turn cost.
     */
    RubricResult evaluateRubric(RubricContext context);
}
