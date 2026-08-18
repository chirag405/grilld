package com.grilld.backend.session;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
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
 * Proves SessionService's scale-tier calibration and user-override path
 * (docs/product-and-architecture.md §4). Mocks only AiServiceClient, same
 * pattern as ContradictionDetectionTest/RubricGateTest.
 */
@Testcontainers
@SpringBootTest
class ScaleCalibrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    ProjectBriefRepository briefRepository;

    @Autowired
    UserService userService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    @Test
    void calibrationPersistsTierAndReasoningUnmarkedAsOverridden() {
        User user = userService.findOrCreateFromGoogle("calibration-google-id", "calibration@example.com", null, null);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder, pre-revenue", List.of("solo", "pre-revenue")));

        ScaleCalibrationResult result = sessionService.calibrateScale(started.sessionId());

        assertEquals("T1", result.tier());

        ProjectBrief brief = briefRepository.findBySessionId(started.sessionId()).orElseThrow();
        assertEquals("T1", brief.getScaleTier());
        assertEquals("solo builder, pre-revenue", brief.getScaleTierReasoning());
        assertFalse(brief.isScaleTierOverridden());
    }

    @Test
    void userOverrideMarksTierAsOverriddenAndKeepsReasoning() {
        User user = userService.findOrCreateFromGoogle("override-google-id", "override@example.com", null, null);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T0", "weekend project", List.of("weekend")));
        sessionService.calibrateScale(started.sessionId());

        sessionService.overrideScaleTier(started.sessionId(), "T2");

        ProjectBrief brief = briefRepository.findBySessionId(started.sessionId()).orElseThrow();
        assertEquals("T2", brief.getScaleTier());
        assertTrue(brief.isScaleTierOverridden());
        assertEquals("weekend project", brief.getScaleTierReasoning(),
                "override changes the tier but shouldn't invent new reasoning text");
    }
}
