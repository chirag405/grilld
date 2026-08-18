package com.grilld.backend.session;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.RubricContext;
import com.grilld.backend.aiservice.RubricResult;
import com.grilld.backend.slot.RubricEvaluation;
import com.grilld.backend.slot.RubricEvaluationRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Proves SessionService's rubric gate (docs/product-and-architecture.md §7):
 * when the Interrogator signals readyToConclude, the Rubric Agent gets the
 * final say, not the Interrogator itself. Mocks only AiServiceClient - every
 * repository is real against a live Testcontainers Postgres, same pattern as
 * ContradictionDetectionTest.
 */
@Testcontainers
@SpringBootTest
class RubricGateTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    RubricEvaluationRepository rubricEvaluationRepository;

    @Autowired
    UserService userService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    @Test
    void acceptedRubricActuallyConcludesTheSession() {
        User user = userService.findOrCreateFromGoogle("rubric-accept-google-id", "rubric-accept@example.com", null, null);

        InterrogatorTurnResult.NextQuestion firstQuestion = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), firstQuestion, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");
        var sessionId = started.sessionId();

        // The Interrogator proposes concluding on this answer.
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), null, true));
        when(aiServiceClient.evaluateRubric(ArgumentMatchers.any())).thenReturn(
                new RubricResult(
                        List.of(new RubricResult.DimensionResult("problem_clarity", "PASS", "clear enough")),
                        "accept", List.of()));

        SessionService.TurnAnswerResult result = sessionService.submitAnswer(sessionId, "Meeting scheduling conflicts.");

        assertTrue(result.concluded(), "an accepted rubric must actually conclude the session");

        List<RubricEvaluation> evaluations = rubricEvaluationRepository.findBySessionIdOrderByAtTurnDesc(sessionId);
        assertEquals(1, evaluations.size());
        assertEquals(RubricEvaluation.Verdict.accept, evaluations.get(0).getVerdict());
    }

    @Test
    void rejectedRubricAsksOneMoreTargetedQuestionInsteadOfConcluding() {
        User user = userService.findOrCreateFromGoogle("rubric-reject-google-id", "rubric-reject@example.com", null, null);

        InterrogatorTurnResult.NextQuestion firstQuestion = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), firstQuestion, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");
        var sessionId = started.sessionId();

        // First call after the answer: Interrogator proposes concluding.
        // Second call (the gate's retry, with open_gaps injected): a real targeted follow-up.
        InterrogatorTurnResult.NextQuestion gapTargetedQuestion = new InterrogatorTurnResult.NextQuestion(
                "You haven't said how many people would use this - roughly how many?",
                List.of("scale_expectation"), "CONCRETIZATION", "number", "closing the scale gap");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any()))
                .thenReturn(new InterrogatorTurnResult(List.of(), List.of(), List.of(), null, true))
                .thenReturn(new InterrogatorTurnResult(List.of(), List.of(), List.of(), gapTargetedQuestion, false));
        when(aiServiceClient.evaluateRubric(ArgumentMatchers.any())).thenReturn(
                new RubricResult(
                        List.of(new RubricResult.DimensionResult("scale_concreteness", "FAIL", "no numbers given")),
                        "probe_further", List.of("scale_concreteness: no numbers given")));

        SessionService.TurnAnswerResult result = sessionService.submitAnswer(sessionId, "Meeting scheduling conflicts.");

        assertFalse(result.concluded(), "a rejected rubric must not conclude the session");
        assertEquals(gapTargetedQuestion.text(), result.question());

        List<RubricEvaluation> evaluations = rubricEvaluationRepository.findBySessionIdOrderByAtTurnDesc(sessionId);
        assertEquals(1, evaluations.size());
        assertEquals(RubricEvaluation.Verdict.probe_further, evaluations.get(0).getVerdict());
    }
}
