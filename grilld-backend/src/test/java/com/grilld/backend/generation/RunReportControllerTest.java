package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationProgressEvent;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.auth.TokenService;
import com.grilld.backend.billing.CreditService;
import com.grilld.backend.session.SessionService;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Run Report reaches an actual client over real HTTP (through
 * Spring Security's real JWT filter, not a bypassed one) - the plain poll
 * endpoint and the SSE stream both wired through Spring's real dispatcher,
 * not just GenerationService's persisted row. Same auth pattern as
 * SessionFlowIntegrationTest (MockMvc + a real TokenService-issued JWT, no
 * real Google login needed). Reuses the SyncTaskExecutor override from
 * GenerationServiceTest so the run has already finished by the time each
 * request goes out - no polling/sleeping needed.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RunReportControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionService sessionService;

    @Autowired
    GenerationService generationService;

    @Autowired
    UserService userService;

    @Autowired
    TokenService tokenService;

    @Autowired
    CreditService creditService;

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

    private record CalibratedSession(UUID sessionId, UUID userId) {
    }

    private CalibratedSession startCalibratedSession(String googleId, String email) {
        User user = userService.findOrCreateFromGoogle(googleId, email, null, null);
        // No free signup grant any more - top up in test setup so generation's
        // credit pre-authorization has something to spend.
        creditService.grantIdempotent(user.getId(), 60, "test-setup-grant:" + googleId);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder", List.of("solo")));
        sessionService.calibrateScale(started.sessionId());
        return new CalibratedSession(started.sessionId(), user.getId());
    }

    @SuppressWarnings("unchecked")
    private void stubOneAgentRun() {
        when(aiServiceClient.generateBlueprint(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Consumer<GenerationProgressEvent> onProgress = invocation.getArgument(4);
                    onProgress.accept(new GenerationProgressEvent(
                            "market_analyst", GenerationProgressEvent.Status.STARTED, null, List.of(), null, null));
                    onProgress.accept(new GenerationProgressEvent(
                            "market_analyst", GenerationProgressEvent.Status.COMPLETED,
                            "Researched the real market for this idea.", List.of("/docs/MARKET_ANALYSIS.md"), 100, 50));
                    return new GenerationResult(Map.of("/docs/MARKET_ANALYSIS.md", "content"));
                });
    }

    @Test
    void reportEndpointReturnsThePersistedRunReportOverRealHttp() throws Exception {
        var calibrated = startCalibratedSession("run-report-poll-google-id", "run-report-poll@example.com");
        String token = tokenService.issueFor(userService.findOrCreateFromGoogle(
                "run-report-poll-google-id", "run-report-poll@example.com", null, null));
        stubOneAgentRun();
        GenerationService.GenerationRunResult result = generationService.generate(calibrated.sessionId(), calibrated.userId());

        String body = mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/report", calibrated.sessionId(), result.runId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String status = JsonPath.read(body, "$.status");
        String runReportMd = JsonPath.read(body, "$.runReportMd");
        assertTrue(status.equals("COMPLETED"));
        assertTrue(runReportMd.contains("✓ Market Analyst — Researched the real market for this idea."),
                "expected the completed agent's narration in the polled report, got:\n" + runReportMd);
    }

    @Test
    void eventsEndpointStreamsTheFinalStateAndCloses() throws Exception {
        var calibrated = startCalibratedSession("run-report-sse-google-id", "run-report-sse@example.com");
        String token = tokenService.issueFor(userService.findOrCreateFromGoogle(
                "run-report-sse-google-id", "run-report-sse@example.com", null, null));
        stubOneAgentRun();
        GenerationService.GenerationRunResult result = generationService.generate(calibrated.sessionId(), calibrated.userId());

        // The run already finished (SyncTaskExecutor), so subscribing now should get
        // exactly one "report" SSE frame with the final state, then the stream closes.
        MvcResult mvcResult = mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/events", calibrated.sessionId(), result.runId())
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("report"), "expected an SSE 'report' event, got:\n" + body);
        assertTrue(body.contains("COMPLETED"));
    }

    @Test
    void reportEndpointRejectsAnotherUsersToken() throws Exception {
        var calibrated = startCalibratedSession("run-report-owner-google-id", "run-report-owner@example.com");
        stubOneAgentRun();
        GenerationService.GenerationRunResult result = generationService.generate(calibrated.sessionId(), calibrated.userId());

        String intruderToken = tokenService.issueFor(
                userService.findOrCreateFromGoogle("run-report-intruder-google-id", "run-report-intruder@example.com", null, null));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/report", calibrated.sessionId(), result.runId())
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isForbidden());
    }
}
