package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBrief;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Proves the reduced-but-real cost circuit breaker (§10.6): trips
 * kill_switch_active when real agent_executions token spend crosses the
 * configured daily_spend_cap_usd, leaves it alone when spend is well under
 * the cap, and only clears via the explicit manual reset - never on its own.
 */
@Testcontainers
@SpringBootTest
class CostCircuitBreakerServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    ProjectBriefRepository briefRepository;

    @Autowired
    GenerationRunRepository generationRunRepository;

    @Autowired
    AgentExecutionRepository agentExecutionRepository;

    @Autowired
    PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    UserService userService;

    @Autowired
    CostCircuitBreakerService costCircuitBreakerService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    private UUID freshRunId(String googleId, String email) {
        User user = userService.findOrCreateFromGoogle(googleId, email, null, null);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder", List.of("solo")));
        sessionService.calibrateScale(started.sessionId());

        ProjectBrief brief = briefRepository.findBySessionId(started.sessionId()).orElseThrow();
        GenerationRun run = generationRunRepository.save(new GenerationRun(brief.getId()));
        return run.getId();
    }

    private void setCap(String usd) {
        PlatformSetting cap = platformSettingsRepository.findById("daily_spend_cap_usd").orElseThrow();
        cap.updateValue(usd);
        platformSettingsRepository.save(cap);
    }

    @Test
    void tripsTheKillSwitchWhenSpendCrossesTheCap() {
        setCap("0.001"); // a fraction of a cent - any real token usage clears it
        UUID runId = freshRunId("cost-trip-google-id", "cost-trip@example.com");

        AgentExecution execution = new AgentExecution(runId, "market_analyst");
        execution.markCompleted("/docs/MARKET_ANALYSIS.md", "Done.", 5000, 2000);
        agentExecutionRepository.save(execution);

        costCircuitBreakerService.checkSpend();

        assertTrue(costCircuitBreakerService.isKillSwitchActive());

        costCircuitBreakerService.resetKillSwitch(); // leave global state clean for other tests sharing this context
    }

    @Test
    void leavesTheKillSwitchOffWhenSpendIsWellUnderTheCap() {
        setCap("25.00"); // V1's seeded default
        UUID runId = freshRunId("cost-safe-google-id", "cost-safe@example.com");

        AgentExecution execution = new AgentExecution(runId, "market_analyst");
        execution.markCompleted("/docs/MARKET_ANALYSIS.md", "Done.", 100, 50);
        agentExecutionRepository.save(execution);

        costCircuitBreakerService.checkSpend();

        assertFalse(costCircuitBreakerService.isKillSwitchActive());
    }

    @Test
    void resetClearsAnAlreadyTrippedSwitch() {
        setCap("0.001");
        UUID runId = freshRunId("cost-reset-google-id", "cost-reset@example.com");
        AgentExecution execution = new AgentExecution(runId, "market_analyst");
        execution.markCompleted("/docs/MARKET_ANALYSIS.md", "Done.", 5000, 2000);
        agentExecutionRepository.save(execution);
        costCircuitBreakerService.checkSpend();
        assertTrue(costCircuitBreakerService.isKillSwitchActive(), "precondition: switch should be tripped");

        costCircuitBreakerService.resetKillSwitch();

        assertFalse(costCircuitBreakerService.isKillSwitchActive());
    }
}
