package com.grilld.backend.session;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests SessionService's contradiction-detection logic in isolation - mocks
 * only AiServiceClient (the external boundary) via @MockitoBean, keeping
 * every repository real against a live Testcontainers Postgres. Doesn't need
 * the real Python service: the scenario is fully scripted here, which is
 * exactly the point - this proves Java's own logic is correct regardless of
 * what the AI side ever sends.
 */
@Testcontainers
@SpringBootTest
class ContradictionDetectionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    SlotRepository slotRepository;

    @Autowired
    UserService userService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    @Test
    void conflictingAnswerSpawnsResolutionSlotInsteadOfOverwriting() {
        User user = userService.findOrCreateFromGoogle("contradiction-test-google-id", "contradiction@example.com", null, null);

        InterrogatorTurnResult.NextQuestion firstQuestion = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), firstQuestion, false));

        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");
        var sessionId = started.sessionId();

        // Turn 1: establish problem_statement = "double-booking meetings"
        InterrogatorTurnResult.ExtractedFact firstFact = new InterrogatorTurnResult.ExtractedFact(
                "problem_statement", "double-booking meetings", 0.9);
        InterrogatorTurnResult.NextQuestion secondQuestion = new InterrogatorTurnResult.NextQuestion(
                "How many users?", List.of("scale_expectation"), "CONCRETIZATION", "number", "scale");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(firstFact), List.of(), List.of(), secondQuestion, false));
        sessionService.submitAnswer(sessionId, "People keep double-booking meetings on our team.");

        Slot problemStatement = slotRepository.findBySessionIdAndSlotKey(sessionId, "problem_statement").orElseThrow();
        assertEquals(Slot.Status.FILLED, problemStatement.getStatus());
        assertEquals("double-booking meetings", problemStatement.getValue());

        // Turn 2: a CONTRADICTING fact for the same slot - a different problem entirely
        InterrogatorTurnResult.ExtractedFact contradictingFact = new InterrogatorTurnResult.ExtractedFact(
                "problem_statement", "invoice tracking is a mess", 0.85);
        InterrogatorTurnResult.NextQuestion thirdQuestion = new InterrogatorTurnResult.NextQuestion(
                "Which is it?", List.of("problem_statement"), "CONTRADICTION_RESOLUTION", "text", "resolve");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(contradictingFact), List.of(), List.of(), thirdQuestion, false));
        sessionService.submitAnswer(sessionId, "Actually it's about invoice tracking.");

        // The original slot must NOT have been silently overwritten
        Slot stillOriginal = slotRepository.findBySessionIdAndSlotKey(sessionId, "problem_statement").orElseThrow();
        assertEquals("double-booking meetings", stillOriginal.getValue(),
                "a contradicting fact must not silently overwrite the existing value");

        // A resolution slot must have been created, flagging the conflict
        List<Slot> allSlots = slotRepository.findBySessionId(sessionId);
        Optional<Slot> resolutionSlot = allSlots.stream()
                .filter(s -> s.getSlotKey().startsWith("problem_statement_contradiction_turn_"))
                .findFirst();
        assertTrue(resolutionSlot.isPresent(), "expected a contradiction-resolution PROBE slot to be created");
        assertEquals(Slot.Origin.PROBE, resolutionSlot.get().getOrigin());
        assertTrue(resolutionSlot.get().getDescription().contains("double-booking meetings"));
        assertTrue(resolutionSlot.get().getDescription().contains("invoice tracking is a mess"));
        // problem_statement is a SEED slot - the RevisionClassifier (§7) should have
        // classified this as MAJOR_REVISION, not just an ordinary contradiction.
        assertTrue(resolutionSlot.get().getDescription().startsWith("[MAJOR REVISION]"),
                "expected a SEED-slot contradiction to be flagged as a major revision, got: "
                        + resolutionSlot.get().getDescription());
    }

    @Test
    void contradictingANonSeedSlotWithNoBlastRadiusIsFlaggedAsMinor() {
        User user = userService.findOrCreateFromGoogle("contradiction-minor-google-id", "contradiction-minor@example.com", null, null);

        InterrogatorTurnResult.NextQuestion firstQuestion = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), firstQuestion, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");
        var sessionId = started.sessionId();

        // Turn 1: spawn and fill a DERIVED slot (monetization_intent), parented off problem_statement.
        InterrogatorTurnResult.NewSlot derivedSlot = new InterrogatorTurnResult.NewSlot(
                "monetization_intent", "Whether this makes money", "DERIVED", 3, "problem_statement");
        InterrogatorTurnResult.ExtractedFact firstFact = new InterrogatorTurnResult.ExtractedFact(
                "monetization_intent", "free forever", 0.9);
        InterrogatorTurnResult.NextQuestion secondQuestion = new InterrogatorTurnResult.NextQuestion(
                "How many users?", List.of("scale_expectation"), "CONCRETIZATION", "number", "scale");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(derivedSlot), List.of(), secondQuestion, false));
        sessionService.submitAnswer(sessionId, "Whatever gets it built.");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(firstFact), List.of(), List.of(), secondQuestion, false));
        sessionService.submitAnswer(sessionId, "Free forever, no monetization.");

        Slot parent = slotRepository.findBySessionIdAndSlotKey(sessionId, "problem_statement").orElseThrow();
        assertTrue(parent.getUnlocks().contains("monetization_intent"),
                "expected the parent-child link to be wired up: parentSlotKey -> unlocks");

        // Turn 2: contradict the DERIVED slot - not SEED, no descendants of its own.
        InterrogatorTurnResult.ExtractedFact contradictingFact = new InterrogatorTurnResult.ExtractedFact(
                "monetization_intent", "freemium", 0.85);
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(contradictingFact), List.of(), List.of(), secondQuestion, false));
        sessionService.submitAnswer(sessionId, "Actually freemium.");

        List<Slot> allSlots = slotRepository.findBySessionId(sessionId);
        Optional<Slot> resolutionSlot = allSlots.stream()
                .filter(s -> s.getSlotKey().startsWith("monetization_intent_contradiction_turn_"))
                .findFirst();
        assertTrue(resolutionSlot.isPresent());
        assertFalse(resolutionSlot.get().getDescription().startsWith("[MAJOR REVISION]"),
                "expected a non-SEED, no-descendant contradiction to stay MINOR, got: "
                        + resolutionSlot.get().getDescription());
    }
}
