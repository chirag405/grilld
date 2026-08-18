package com.grilld.backend.generation;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory fan-out from a {@link GenerationRun}'s updates (assembled by
 * {@link RunReportService}, pushed from {@link GenerationService}'s
 * background thread) to any client SSE connections currently watching that
 * run (§10.3: "they watch it update live over SSE/WebSocket"). No
 * persistence of its own - a client that reconnects gets the current state
 * via RunReportController's plain GET, since the report itself is already
 * durable on the {@code generation_runs} row.
 */
@Component
public class RunReportBroadcaster {

    private final Map<UUID, List<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();
    private final AgentExecutionRepository agentExecutionRepository;

    public RunReportBroadcaster(AgentExecutionRepository agentExecutionRepository) {
        this.agentExecutionRepository = agentExecutionRepository;
    }

    public SseEmitter subscribe(UUID runId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByRun.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> deregister(runId, emitter));
        emitter.onTimeout(() -> deregister(runId, emitter));
        emitter.onError(ex -> deregister(runId, emitter));
        return emitter;
    }

    /** Sends the current state to one newly-subscribed emitter, without touching the others. */
    public void sendCurrentState(SseEmitter emitter, GenerationRun run) {
        send(emitter, run);
    }

    /** Sends the current state to every emitter subscribed to this run. */
    public void publish(UUID runId, GenerationRun run) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, run);
        }
    }

    private void send(SseEmitter emitter, GenerationRun run) {
        try {
            emitter.send(SseEmitter.event().name("report")
                    .data(RunReportUpdate.from(run,
                            agentExecutionRepository.findByRunIdOrderByStartedAtAsc(run.getId()))));
            if (run.getStatus() != GenerationRun.Status.IN_PROGRESS) {
                emitter.complete();
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void deregister(UUID runId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByRun.remove(runId);
        }
    }
}
