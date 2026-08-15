package com.grilld.backend.aiservice;

import java.util.List;
import java.util.UUID;

/**
 * What the Rubric Agent needs to judge a brief - deliberately not
 * WorkingContext, which is shaped for the Interrogator's per-turn view
 * (top-N ranked OPEN slots, last few turns). The Rubric Agent needs the full
 * picture: every slot regardless of status, so it can see FILLED evidence,
 * WAIVED exclusions, and remaining OPEN gaps all at once.
 */
public record RubricContext(
        UUID sessionId,
        String briefJson,
        List<SlotSnapshot> slots
) {
    public record SlotSnapshot(String slotKey, String status, String value, int importance) {
    }
}
