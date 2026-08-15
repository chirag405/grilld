package com.grilld.backend.memory;

import java.util.List;
import java.util.UUID;

/**
 * Exactly what gets sent to the Python AI service for one interrogation turn -
 * the "Layer 2" working context from docs/product-and-architecture.md §2.1.
 * Assembled fresh every turn from Postgres (WorkingContextAssembler); never
 * accumulated or carried over from a previous call. This is what makes turn 40
 * cost the same as turn 4 - see interrogation-engine.md §3.
 */
public record WorkingContext(
        UUID sessionId,
        String rawIdea,
        String compactedBriefSummary,
        List<RecentTurn> recentTurns,
        List<RankedSlot> openSlotsRanked,
        List<String> answeredTopics
) {
    public record RecentTurn(
            int turnNumber,
            String questionText,
            String answerText
    ) {
    }

    public record RankedSlot(
            String slotKey,
            String description,
            int importance,
            double priority
    ) {
    }
}
