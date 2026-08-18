package com.grilld.backend.aiservice;

import java.util.List;

/**
 * Mirrors the Interrogator's structured-output contract exactly
 * (docs/interrogation-engine.md §3's per-turn loop, step 2). This is the
 * shape returned by whatever answers a turn - a stub today (StubAiServiceClient),
 * the real Python service via the LangGraph server API from Phase 3 on
 * (docs/decisions-and-technical-architecture.md §11.3). Nothing on the Spring
 * side should need to change when the stub is swapped for the real thing.
 */
public record InterrogatorTurnResult(
        List<ExtractedFact> extractedFacts,
        List<NewSlot> newSlots,
        List<WaivedSlot> waivedSlots,
        String intent,
        String assistantMessage,
        ReasoningTrace reasoningTrace,
        NextQuestion nextQuestion, // null when readyToConclude is true
        boolean readyToConclude
) {
    public InterrogatorTurnResult(List<ExtractedFact> extractedFacts, List<NewSlot> newSlots,
                                   List<WaivedSlot> waivedSlots, NextQuestion nextQuestion, boolean readyToConclude) {
        this(extractedFacts, newSlots, waivedSlots, "ANSWER", null,
                new ReasoningTrace("Processed the response.", List.of(), List.of()), nextQuestion, readyToConclude);
    }

    public record ReasoningTrace(String summary, List<String> decisions, List<String> assumptions) {
    }
    public record ExtractedFact(String slotKey, String value, double confidence) {
    }

    public record NewSlot(String key, String description, String origin, int importance, String parentSlotKey) {
    }

    public record WaivedSlot(String key, String reason) {
    }

    public record NextQuestion(
            String text,
            List<String> targetsSlots,
            String technique,
            String inputMode,
            String whyAsking,
            List<String> chipOptions
    ) {
        // Existing call sites (tests, mostly) predate chipOptions and don't care about
        // it - this overload keeps them compiling instead of forcing an empty-list
        // argument onto every one of them for a field they're not testing.
        public NextQuestion(String text, List<String> targetsSlots, String technique, String inputMode,
                             String whyAsking) {
            this(text, targetsSlots, technique, inputMode, whyAsking, List.of());
        }
    }
}
