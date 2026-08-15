package com.grilld.backend.session;

import com.grilld.backend.slot.Slot;

import java.util.List;
import java.util.UUID;

/**
 * The ~8 universal slots present for every project, per
 * docs/interrogation-engine.md §2. Not questions - knowledge requirements;
 * the Interrogator decides how to surface each one once it exists (Phase 4).
 * Created once, at session start, so the slot graph always starts from the
 * same baseline regardless of which project idea comes in.
 */
final class SeedSlots {

    private SeedSlots() {
    }

    static List<Slot> forSession(UUID sessionId) {
        return List.of(
                seed(sessionId, "problem_statement", "Who this is for and what's broken today", 5),
                seed(sessionId, "target_user", "The specific person who has this problem", 5),
                seed(sessionId, "scale_expectation", "Concrete user/traffic numbers, not adjectives", 5),
                seed(sessionId, "timeline", "When this needs to ship, and how firm that date is", 4),
                seed(sessionId, "team_shape", "Solo, pair, or team - and how that's expected to change", 5),
                seed(sessionId, "builder_skillset", "What the builder already knows vs. wants to learn", 4),
                seed(sessionId, "success_definition", "What \"working\" looks like, in the builder's own words", 4),
                seed(sessionId, "hard_constraints", "Non-negotiables: budget, compliance, must-integrate-with", 4)
        );
    }

    private static Slot seed(UUID sessionId, String key, String description, int importance) {
        return new Slot(sessionId, key, description, Slot.Origin.SEED, importance, 0);
    }
}
