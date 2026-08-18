package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationResult;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the reduced-scope resume sweep (§10.5, GenerationResumeSweep) does
 * exactly what it claims: re-triggers a run stuck IN_PROGRESS with no
 * activity since the configured threshold, leaves a fresh IN_PROGRESS run
 * alone, and never touches a COMPLETED run - purely from generation_runs
 * timestamps, no Python-side status check (see GenerationResumeSweep's own
 * Javadoc for why that half is deliberately deferred).
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "grilld.generation.resume-sweep.stale-after-ms=60000")
class GenerationResumeSweepTest {

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
    UserService userService;

    @Autowired
    GenerationResumeSweep generationResumeSweep;

    @Autowired
    JdbcTemplate jdbcTemplate;

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

    private UUID calibratedBriefId(String googleId, String email) {
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
        return brief.getId();
    }

    @Test
    void sweepResumesOnlyTheStaleInProgressRun() throws InterruptedException {
        UUID staleBriefId = calibratedBriefId("resume-stale-google-id", "resume-stale@example.com");
        UUID freshBriefId = calibratedBriefId("resume-fresh-google-id", "resume-fresh@example.com");
        UUID completedBriefId = calibratedBriefId("resume-done-google-id", "resume-done@example.com");

        GenerationRun staleRun = generationRunRepository.save(new GenerationRun(staleBriefId));
        jdbcTemplate.update("update generation_runs set updated_at = now() - interval '2 minutes' where id = ?",
                staleRun.getId());

        GenerationRun freshRun = generationRunRepository.save(new GenerationRun(freshBriefId));

        GenerationRun completedRun = new GenerationRun(completedBriefId);
        completedRun.markCompleted();
        completedRun = generationRunRepository.save(completedRun);

        when(aiServiceClient.generateBlueprint(
                eq(staleRun.getId()), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new GenerationResult(Map.of()));

        generationResumeSweep.sweep();

        verify(aiServiceClient, times(1)).generateBlueprint(
                eq(staleRun.getId()), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(aiServiceClient, never()).generateBlueprint(
                eq(freshRun.getId()), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        GenerationRun reloadedStale = generationRunRepository.findById(staleRun.getId()).orElseThrow();
        assertEquals(GenerationRun.Status.COMPLETED, reloadedStale.getStatus(),
                "resumeStaleRun's dispatched runGeneration should have run synchronously (SyncTaskExecutor) and completed");

        GenerationRun reloadedFresh = generationRunRepository.findById(freshRun.getId()).orElseThrow();
        assertEquals(GenerationRun.Status.IN_PROGRESS, reloadedFresh.getStatus(), "fresh run should be untouched");

        GenerationRun reloadedCompleted = generationRunRepository.findById(completedRun.getId()).orElseThrow();
        assertEquals(GenerationRun.Status.COMPLETED, reloadedCompleted.getStatus());
    }
}
