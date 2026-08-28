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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the "preview a document before downloading the whole package" path
 * (Phase 12) - real content, over real HTTP through real Spring Security,
 * same shape as PackageControllerTest.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class GeneratedDocumentControllerTest {

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

    @Test
    void ownerCanReadADocumentsRealContentButAnotherUserCannot() throws Exception {
        User owner = userService.findOrCreateFromGoogle("doc-owner-google-id", "doc-owner@example.com", null, null);
        String ownerToken = tokenService.issueFor(owner);
        creditService.grantIdempotent(owner.getId(), 60, "test-setup-grant:doc-owner-google-id");

        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(owner.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder", List.of("solo")));
        sessionService.calibrateScale(started.sessionId());

        when(aiServiceClient.generateBlueprint(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Consumer<GenerationProgressEvent> onProgress = invocation.getArgument(4);
                    onProgress.accept(new GenerationProgressEvent(
                            "market_analyst", GenerationProgressEvent.Status.STARTED, null, List.of(), null, null));
                    onProgress.accept(new GenerationProgressEvent(
                            "market_analyst", GenerationProgressEvent.Status.COMPLETED,
                            "Done.", List.of("/docs/MARKET_ANALYSIS.md"), 100, 50));
                    return new GenerationResult(Map.of("/docs/MARKET_ANALYSIS.md", "# Market\n\nreal content"));
                });

        GenerationService.GenerationRunResult result = generationService.generate(started.sessionId(), owner.getId());

        String body = mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/documents", started.sessionId(), result.runId())
                        .param("path", "/docs/MARKET_ANALYSIS.md")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("/docs/MARKET_ANALYSIS.md", JsonPath.read(body, "$.path"));
        assertEquals("# Market\n\nreal content", JsonPath.read(body, "$.content"));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/documents", started.sessionId(), result.runId())
                        .param("path", "/docs/DOES_NOT_EXIST.md")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        String intruderToken = tokenService.issueFor(
                userService.findOrCreateFromGoogle("doc-intruder-google-id", "doc-intruder@example.com", null, null));
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/documents", started.sessionId(), result.runId())
                        .param("path", "/docs/MARKET_ANALYSIS.md")
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isForbidden());
    }
}
