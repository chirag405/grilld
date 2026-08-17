package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationProgressEvent;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.auth.TokenService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves a full generate() run, end to end, leaves a real downloadable
 * package behind - through real HTTP + Spring Security, not just
 * PackagerService in isolation (that's PackagerServiceTest's job).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "grilld.packages.local-storage-dir=target/test-packages")
class PackageControllerTest {

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
    void packageIsReadyAndDownloadableRightAfterGenerate() throws Exception {
        User user = userService.findOrCreateFromGoogle("package-e2e-google-id", "package-e2e@example.com");
        String token = tokenService.issueFor(user);

        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

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
                    return new GenerationResult(Map.of("/docs/MARKET_ANALYSIS.md", "market content"));
                });

        GenerationService.GenerationRunResult result = generationService.generate(started.sessionId(), user.getId());

        String statusBody = mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/package",
                        started.sessionId(), result.runId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("READY", (String) JsonPath.read(statusBody, "$.status"));
        List<String> paths = JsonPath.read(statusBody, "$.documentPaths");
        assertTrue(paths.contains("/docs/MARKET_ANALYSIS.md"));

        byte[] zipBytes = mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/package/download",
                        started.sessionId(), result.runId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            var entry = zip.getNextEntry();
            assertEquals("docs/MARKET_ANALYSIS.md", entry.getName());
            assertEquals("market content", new String(zip.readAllBytes()));
        }

        String intruderToken = tokenService.issueFor(
                userService.findOrCreateFromGoogle("package-intruder-google-id", "package-intruder@example.com"));
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/runs/{runId}/package/download",
                        started.sessionId(), result.runId())
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isForbidden());
    }
}
