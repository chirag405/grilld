package com.grilld.backend.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Finds {@link GenerationRun}s stuck at IN_PROGRESS with no recent activity
 * and re-triggers them (docs/decisions-and-technical-architecture.md §10.5's
 * resume sweep) - the case where this JVM restarted (deploy, crash) mid-run
 * and the background thread that was working on it simply vanished with it.
 *
 * <p>Reduced scope, agreed explicitly rather than built silently: §10.5
 * specs reconciling against the Python service's own run status before
 * deciding whether to re-trigger or just resume watching. That needs a
 * durable, restart-surviving status API on the Python side - this project
 * currently talks to {@code langgraph dev}, whose own thread/run bookkeeping
 * is itself in-memory and ephemeral (see grilld-ai-service/LEARNING.md), so
 * querying it wouldn't actually answer "did Python lose this run too." This
 * sweep is Spring-side only: staleness is purely a timestamp comparison
 * against this database, and every stale run is unconditionally re-triggered,
 * trusting the Python side's own LangGraph checkpointer (already proven,
 * Phase 3) to resume correctly rather than redo completed work. Revisit once
 * {@code langgraph up} (a durable, Postgres-backed deployment) is real.
 */
@Component
public class GenerationResumeSweep {

    private final GenerationRunRepository generationRunRepository;
    private final GenerationService generationService;
    private final long staleAfterMs;

    public GenerationResumeSweep(GenerationRunRepository generationRunRepository, GenerationService generationService,
                                  @Value("${grilld.generation.resume-sweep.stale-after-ms:900000}") long staleAfterMs) {
        this.generationRunRepository = generationRunRepository;
        this.generationService = generationService;
        this.staleAfterMs = staleAfterMs;
    }

    @Scheduled(fixedDelayString = "${grilld.generation.resume-sweep.interval-ms:300000}")
    public void sweep() {
        Instant threshold = Instant.now().minusMillis(staleAfterMs);
        List<GenerationRun> staleRuns = generationRunRepository
                .findByStatusAndUpdatedAtBefore(GenerationRun.Status.IN_PROGRESS, threshold);
        for (GenerationRun run : staleRuns) {
            generationService.resumeStaleRun(run);
        }
    }
}
