package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationProgressEvent;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.GenerationBlockedException;
import com.grilld.backend.session.SessionService;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves GenerationService's control flow against a mocked AiServiceClient -
 * same pattern as RubricGateTest/ScaleCalibrationTest. Simulates the
 * streaming contract (onProgress fired with STARTED/COMPLETED events) since
 * that's what a real HttpAiServiceClient does now - does not exercise the
 * real specialist roster or SSE parsing; that's
 * tests/integration_tests/test_graph.py's job on the Python side (real
 * Claude, real web search, real files) plus a live curl-driven check for the
 * SSE parsing itself.
 *
 * generate() now hands the run off to a background TaskExecutor and returns
 * immediately (Phase 6) - this test overrides that executor with a
 * SyncTaskExecutor (runs the task on the calling thread) so assertions right
 * after generate() see the finished result deterministically, with no
 * polling/sleeping needed.
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

    @Autowired
    PlatformSettingsRepository platformSettingsRepository;

    @MockitoBean
    AiServiceClient aiServiceClient;

    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean
        @Primary
        TaskExecutor testGenerationExecutor() {
            return new SyncTaskExecutor();
        }
    }

    private UUID startCalibratedSession(String googleId, String email) {
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

    /** Stubs a streamed run that fires STARTED then COMPLETED for each (agentName, path, narration) triple. */
    @SuppressWarnings("unchecked")
    private void stubStreamedRun(List<Object[]> agentSteps, Map<String, String> finalFiles) {
        when(aiServiceClient.generateBlueprint(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Consumer<GenerationProgressEvent> onProgress = invocation.getArgument(4);
                    for (Object[] step : agentSteps) {
                        String agentName = (String) step[0];
                        String path = (String) step[1];
                        String narration = (String) step[2];
                        onProgress.accept(new GenerationProgressEvent(
                                agentName, GenerationProgressEvent.Status.STARTED, null, List.of(), null, null));
                        onProgress.accept(new GenerationProgressEvent(
                                agentName, GenerationProgressEvent.Status.COMPLETED, narration, List.of(path), 100, 50));
                    }
                    return new GenerationResult(finalFiles);
                });
    }

    @Test
    void successfulRunPersistsCompletedRunAndAgentExecutionsWithNarration() {
        var sessionId = startCalibratedSession("gen-success-google-id", "gen-success@example.com");

        Map<String, String> files = new LinkedHashMap<>();
        files.put("/docs/MARKET_ANALYSIS.md", "market content");
        files.put("/docs/STRATEGY.md", "strategy content");
        stubStreamedRun(List.of(
                new Object[]{"market_analyst", "/docs/MARKET_ANALYSIS.md", "Researched the real market for this idea."},
                new Object[]{"strategy_agent", "/docs/STRATEGY.md", "Positioned around the go-to-market gap found."}
        ), files);

        GenerationService.GenerationRunResult result = generationService.generate(sessionId);

        // generate() itself only returns the immediate handle (IN_PROGRESS, no
        // files yet) - the SyncTaskExecutor override means the run has already
        // finished on the calling thread by the time control returns here, so
        // the persisted row (not the return value) is what's asserted on.
        GenerationRun run = generationRunRepository.findById(result.runId()).orElseThrow();
        assertEquals(GenerationRun.Status.COMPLETED, run.getStatus());
        assertTrue(run.getRunReportMd().contains("✓ Market Analyst — Researched the real market for this idea."),
                "expected the Run Report to carry the completed agent's narration, got:\n" + run.getRunReportMd());
        assertTrue(run.getRunReportMd().contains("✓ Strategy Agent — Positioned around the go-to-market gap found."));

        List<AgentExecution> executions = agentExecutionRepository.findByRunId(result.runId());
        assertEquals(2, executions.size(), "one AgentExecution per specialist that actually ran, from real events");

        AgentExecution marketExecution = executions.stream()
                .filter(e -> e.getAgentName().equals("market_analyst")).findFirst().orElseThrow();
        assertEquals(AgentExecution.Status.COMPLETED, marketExecution.getStatus());
        assertEquals("/docs/MARKET_ANALYSIS.md", marketExecution.getOutputRef());
    }

    @Test
    void aiServiceFailureMarksRunFailedRatherThanLeavingItInProgress() {
        var sessionId = startCalibratedSession("gen-failure-google-id", "gen-failure@example.com");

        when(aiServiceClient.generateBlueprint(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("python service unreachable"));

        // The AI-service exception now happens on the background executor, not on
        // this thread - generate() itself no longer throws (see runGeneration()'s
        // catch); the SyncTaskExecutor override still makes this deterministic.
        generationService.generate(sessionId);

        List<GenerationRun> runs = generationRunRepository.findByBriefIdOrderByStartedAtDesc(
                briefRepository.findBySessionId(sessionId).orElseThrow().getId());
        assertEquals(1, runs.size());
        assertEquals(GenerationRun.Status.FAILED, runs.get(0).getStatus());
    }

    @Test
    void anAgentThatStartedButNeverCompletedStaysRunning() {
        // Proves a mid-run failure (the AI service throws partway through streaming)
        // leaves an honest trail: the agents that finished are COMPLETED, the one
        // that was in flight when the error hit stays RUNNING - not silently marked
        // done, not silently dropped. This is exactly what the resume sweep (Phase 6
        // task 4) needs to find.
        var sessionId = startCalibratedSession("gen-partial-google-id", "gen-partial@example.com");

        when(aiServiceClient.generateBlueprint(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Consumer<GenerationProgressEvent> onProgress = invocation.getArgument(4);
                    onProgress.accept(new GenerationProgressEvent(
                            "market_analyst", GenerationProgressEvent.Status.STARTED, null, List.of(), null, null));
                    onProgress.accept(new GenerationProgressEvent(
                            "market_analyst", GenerationProgressEvent.Status.COMPLETED, "Done.", List.of("/docs/MARKET_ANALYSIS.md"), 100, 50));
                    onProgress.accept(new GenerationProgressEvent(
                            "competition_analyst", GenerationProgressEvent.Status.STARTED, null, List.of(), null, null));
                    throw new RuntimeException("connection dropped mid-stream");
                });

        generationService.generate(sessionId);

        UUID briefId = briefRepository.findBySessionId(sessionId).orElseThrow().getId();
        UUID runId = generationRunRepository.findByBriefIdOrderByStartedAtDesc(briefId).get(0).getId();
        List<AgentExecution> executions = agentExecutionRepository.findByRunId(runId);

        AgentExecution market = executions.stream().filter(e -> e.getAgentName().equals("market_analyst")).findFirst().orElseThrow();
        assertEquals(AgentExecution.Status.COMPLETED, market.getStatus());

        AgentExecution competition = executions.stream().filter(e -> e.getAgentName().equals("competition_analyst")).findFirst().orElseThrow();
        assertEquals(AgentExecution.Status.RUNNING, competition.getStatus());
    }

    @Test
    void unresolvedOpenSlotsArePassedThroughToTheAiService() {
        // Every seed slot is still OPEN right after startSession - nothing has been
        // extracted yet, so this proves the "everything unresolved lands in
        // ASSUMPTIONS.md" wiring (product-and-architecture.md §7) actually reaches
        // the AI service, not just that the field exists.
        var sessionId = startCalibratedSession("gen-unresolved-google-id", "gen-unresolved@example.com");

        stubStreamedRun(List.of(), Map.of());

        generationService.generate(sessionId);

        ArgumentCaptor<List<String>> unresolvedCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiServiceClient).generateBlueprint(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                unresolvedCaptor.capture(), ArgumentMatchers.any());

        assertTrue(unresolvedCaptor.getValue().size() >= 8,
                "expected the 8 universal seed slots (interrogation-engine.md §2) to still be OPEN and passed through");
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

    @Test
    void generateRefusesToStartWhileTheCostKillSwitchIsActive() {
        var sessionId = startCalibratedSession("gen-killswitch-google-id", "gen-killswitch@example.com");

        PlatformSetting killSwitch = platformSettingsRepository.findById("kill_switch_active").orElseThrow();
        killSwitch.updateValue("true");
        platformSettingsRepository.save(killSwitch);
        try {
            assertThrows(GenerationBlockedException.class, () -> generationService.generate(sessionId));
        } finally {
            killSwitch.updateValue("false"); // don't poison other tests sharing this context
            platformSettingsRepository.save(killSwitch);
        }
    }
}
