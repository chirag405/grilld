package com.grilld.backend.aiservice;

import com.grilld.backend.memory.WorkingContext;

import java.util.List;
import java.util.UUID;

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

    /**
     * Assigns the hard complexity ceiling for a concluded interview
     * (docs/product-and-architecture.md §4). Called once, right after the
     * Rubric Agent accepts - before any generation run starts, since the
     * tier is shown to the user for override first.
     */
    ScaleCalibrationResult calibrateScale(String briefJson);

    /**
     * Runs the full specialist roster (docs/product-and-architecture.md
     * §3.2) against a finalized, scale-calibrated brief. runId doubles as
     * the LangGraph thread id, one thread per generation attempt - unlike
     * the Interrogator's one-thread-per-interview-session.
     * unresolvedSlotDescriptions carries anything still OPEN when the
     * session concluded (via the rubric gate's "never trap" fallback, or the
     * user's force-conclude escape hatch, §7) - the Orchestrator writes these
     * into ASSUMPTIONS.md prominently rather than silently dropping them.
     */
    GenerationResult generateBlueprint(UUID runId, String briefJson, String scaleTier, List<String> unresolvedSlotDescriptions);
}
