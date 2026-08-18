package com.grilld.backend.generation;

import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Lets a client watch a {@link GenerationRun}'s Run Report live (§10.3) -
 * {@code /events} for a persistent SSE stream (frontend's primary use:
 * diff-highlight the changed lines as they arrive), {@code /report} for a
 * plain poll (initial page load before the SSE connection opens, or a
 * client that doesn't want a long-lived connection).
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/runs/{runId}")
public class RunReportController {

    private final GenerationRunRepository generationRunRepository;
    private final RunReportBroadcaster broadcaster;
    private final GenerationService generationService;
    private final AgentExecutionRepository agentExecutionRepository;

    public RunReportController(GenerationRunRepository generationRunRepository, RunReportBroadcaster broadcaster,
                                GenerationService generationService, AgentExecutionRepository agentExecutionRepository) {
        this.generationRunRepository = generationRunRepository;
        this.broadcaster = broadcaster;
        this.generationService = generationService;
        this.agentExecutionRepository = agentExecutionRepository;
    }

    @GetMapping("/report")
    public RunReportUpdate report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        verifyOwnership(runId, jwt);
        GenerationRun run = findRun(runId);
        long completed = agentExecutionRepository.findByRunIdOrderByStartedAtAsc(runId).stream()
                .filter(execution -> execution.getStatus() == AgentExecution.Status.COMPLETED)
                .count();
        return new RunReportUpdate(run.getStatus().name(), run.getRunReportMd(), run.getFailureReason(),
                completed, RunReportService.AGENT_ROSTER.size());
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        verifyOwnership(runId, jwt);
        GenerationRun run = findRun(runId);
        SseEmitter emitter = broadcaster.subscribe(runId);
        broadcaster.sendCurrentState(emitter, run);
        return emitter;
    }

    private void verifyOwnership(UUID runId, Jwt jwt) {
        UUID owningUserId = generationService.resolveOwningUserId(runId);
        if (!owningUserId.equals(UUID.fromString(jwt.getSubject()))) {
            throw new AccessDeniedException("Run " + runId + " does not belong to the requesting user");
        }
    }

    private GenerationRun findRun(UUID runId) {
        return generationRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("No generation run " + runId));
    }
}
