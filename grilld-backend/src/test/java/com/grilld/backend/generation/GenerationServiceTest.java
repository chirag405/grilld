package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.session.SessionService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Proves GenerationService's control flow against a mocked AiServiceClient -
 * same pattern as RubricGateTest/ScaleCalibrationTest. Does not exercise the
 * real specialist roster; that's tests/integration_tests/test_graph.py's job
 * on the Python side (real Claude, real web search, real files).
 */
@Testcontainers
@SpringBootTest
class GenerationServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    GenerationService generationService;

    @Autowired
    ProjectBriefRepository briefRepository;

    @Autowired
    GenerationRunRepository generationRunRepository;

    @Autowired
    AgentExecutionRepository agentExecutionRepository;

    @Autowired
    UserService userService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    private java.util.UUID startCalibratedSession(String googleId, String email) {
        User user = userService.findOrCreateFromGoogle(googleId, email);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder", List.of("solo")));
        sessionService.calibrateScale(started.sessionId());
        return started.sessionId();
    }

    @Test
    void successfulRunPersistsCompletedRunAndAgentExecutions() {
        var sessionId = startCalibratedSession("gen-success-google-id", "gen-success@example.com");

        Map<String, String> files = new LinkedHashMap<>();
        files.put("/docs/MARKET_ANALYSIS.md", "market content");
        files.put("/docs/COMPETITION.md", "competition content");
        // Deliberately omit some expected paths to prove partial results are handled, not just the happy path.
        when(aiServiceClient.generateBlueprint(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("T1")))
                .thenReturn(new GenerationResult(files));

        GenerationService.GenerationRunResult result = generationService.generate(sessionId);

        assertEquals("COMPLETED", result.status());
        assertEquals(2, result.files().size());

        GenerationRun run = generationRunRepository.findById(result.runId()).orElseThrow();
        assertEquals(GenerationRun.Status.COMPLETED, run.getStatus());

        List<AgentExecution> executions = agentExecutionRepository.findByRunId(result.runId());
        assertEquals(10, executions.size(), "one AgentExecution per specialist regardless of outcome");

        AgentExecution marketExecution = executions.stream()
                .filter(e -> e.getAgentName().equals("market_analyst")).findFirst().orElseThrow();
        assertEquals(AgentExecution.Status.COMPLETED, marketExecution.getStatus());

        AgentExecution roadmapExecution = executions.stream()
                .filter(e -> e.getAgentName().equals("roadmap_agent")).findFirst().orElseThrow();
        assertEquals(AgentExecution.Status.FAILED, roadmapExecution.getStatus(),
                "an agent whose expected file wasn't produced should be recorded as failed, not silently passed");
    }

    @Test
    void aiServiceFailureMarksRunFailedRatherThanLeavingItInProgress() {
        var sessionId = startCalibratedSession("gen-failure-google-id", "gen-failure@example.com");

        when(aiServiceClient.generateBlueprint(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("python service unreachable"));

        assertThrows(RuntimeException.class, () -> generationService.generate(sessionId));

        List<GenerationRun> runs = generationRunRepository.findByBriefIdOrderByStartedAtDesc(
                briefRepository.findBySessionId(sessionId).orElseThrow().getId());
        assertEquals(1, runs.size());
        assertEquals(GenerationRun.Status.FAILED, runs.get(0).getStatus());
    }

    @Test
    void generatingWithoutCalibrationFailsFast() {
        User user = userService.findOrCreateFromGoogle("gen-no-tier-google-id", "gen-no-tier@example.com");
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        assertThrows(IllegalStateException.class, () -> generationService.generate(started.sessionId()));
    }
}
