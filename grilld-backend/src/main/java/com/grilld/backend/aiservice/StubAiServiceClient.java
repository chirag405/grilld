package com.grilld.backend.aiservice;

import com.grilld.backend.memory.WorkingContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Canned responses standing in for the real Python AI service, which doesn't
 * exist until Phase 3. Exercises every branch a real implementation must
 * support - the opening restatement, a mid-interview fact extraction + follow-
 * up, and the conclude path - so the persistence pipeline built around this
 * interface (SessionService) can be proven correct now and left completely
 * unchanged when StubAiServiceClient is swapped for a real one.
 *
 * @Profile("!python-ai-service") means: use this stub in every profile except
 * the one a real implementation will register under once it exists - nothing
 * to remember to remove, the real bean just needs to declare it wins instead.
 */
@Component
@Profile("!python-ai-service")
public class StubAiServiceClient implements AiServiceClient {

    private static final int CONCLUDE_AFTER_TURNS = 3;

    @Override
    public InterrogatorTurnResult nextTurn(WorkingContext context) {
        if (context.recentTurns().isEmpty()) {
            return openingTurn(context);
        }
        if (context.recentTurns().size() >= CONCLUDE_AFTER_TURNS) {
            return new InterrogatorTurnResult(List.of(), List.of(), List.of(), null, true);
        }
        return followUpTurn(context);
    }

    private InterrogatorTurnResult openingTurn(WorkingContext context) {
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "You want to build: \"" + context.rawIdea() + "\". What did I get wrong?",
                List.of("problem_statement"),
                "ASSUMPTION_SURFACING",
                "text",
                "Restating first proves I listened and surfaces corrections cheaply."
        );
        return new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false);
    }

    private InterrogatorTurnResult followUpTurn(WorkingContext context) {
        WorkingContext.RecentTurn lastTurn = context.recentTurns().get(0);
        InterrogatorTurnResult.ExtractedFact fact = new InterrogatorTurnResult.ExtractedFact(
                "problem_statement", lastTurn.answerText(), 0.8);

        // scale_expectation is already a SEED slot (interrogation-engine.md §2) - the
        // question below targets it directly rather than "spawning" a duplicate.
        // monetization_intent genuinely doesn't exist yet: a DERIVED slot, spawned
        // because this particular idea (tracking invoices) implies a money question
        // that a generic seed slot wouldn't cover.
        InterrogatorTurnResult.NewSlot newSlot = new InterrogatorTurnResult.NewSlot(
                "monetization_intent", "Whether this is meant to make money or stay a free personal tool",
                "DERIVED", 3, "problem_statement");

        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "Roughly how many people would use this on day one?",
                List.of("scale_expectation"),
                "CONCRETIZATION",
                "number",
                "Scale changes every downstream recommendation - see product-and-architecture.md §4."
        );

        return new InterrogatorTurnResult(List.of(fact), List.of(newSlot), List.of(), question, false);
    }
}
