package com.grilld.backend.generation;

import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.http.MediaType;
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

    public RunReportController(GenerationRunRepository generationRunRepository, RunReportBroadcaster broadcaster) {
        this.generationRunRepository = generationRunRepository;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/report")
    public RunReportUpdate report(@PathVariable UUID runId) {
        GenerationRun run = findRun(runId);
        return new RunReportUpdate(run.getStatus().name(), run.getRunReportMd(), run.getFailureReason());
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID runId) {
        GenerationRun run = findRun(runId);
        SseEmitter emitter = broadcaster.subscribe(runId);
        broadcaster.sendCurrentState(emitter, run);
        return emitter;
    }

    private GenerationRun findRun(UUID runId) {
        return generationRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("No generation run " + runId));
    }
}
