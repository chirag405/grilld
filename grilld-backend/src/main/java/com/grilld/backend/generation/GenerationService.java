package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationProgressEvent;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.GenerationBlockedException;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Triggers a full generation run - the specialist roster
 * (docs/product-and-architecture.md §3.2) turning a scale-calibrated brief
 * into the blueprint package. Streams live per-specialist progress
 * (docs/decisions-and-technical-architecture.md §11.3, §10.2) rather than
 * Phase 5's single end-of-run response - see AiServiceClient.generateBlueprint's
 * onProgress callback and RunReportService for what reads these rows.
 */
@Service
public class GenerationService {

    private final ProjectBriefRepository briefRepository;
    private final SlotRepository slotRepository;
    private final GenerationRunRepository generationRunRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final AiServiceClient aiServiceClient;
    private final TaskExecutor generationExecutor;
    private final RunReportService runReportService;
    private final RunReportBroadcaster runReportBroadcaster;
    private final CostCircuitBreakerService costCircuitBreakerService;

    public GenerationService(ProjectBriefRepository briefRepository, SlotRepository slotRepository,
                              GenerationRunRepository generationRunRepository,
                              AgentExecutionRepository agentExecutionRepository, AiServiceClient aiServiceClient,
                              TaskExecutor generationExecutor, RunReportService runReportService,
                              RunReportBroadcaster runReportBroadcaster,
                              CostCircuitBreakerService costCircuitBreakerService) {
        this.briefRepository = briefRepository;
        this.slotRepository = slotRepository;
        this.generationRunRepository = generationRunRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.aiServiceClient = aiServiceClient;
        this.generationExecutor = generationExecutor;
        this.runReportService = runReportService;
        this.runReportBroadcaster = runReportBroadcaster;
        this.costCircuitBreakerService = costCircuitBreakerService;
    }

    /**
     * Validates preconditions and creates the {@link GenerationRun} row
     * synchronously (so a caller gets an immediate, honest error for a
     * missing brief/scale tier), then hands the actual multi-minute
     * specialist-roster call off to {@link #generationExecutor} and returns
     * the run id right away - the caller no longer blocks for the whole run.
     * Watch progress via the run's SSE endpoint or by polling its status.
     */
    public GenerationRunResult generate(UUID sessionId) {
        if (costCircuitBreakerService.isKillSwitchActive()) {
            // Same pre-authorization point credits will eventually be checked at
            // (Phase 7, spec-v2 §13) - refuses before any AI-service call, not mid-run.
            throw new GenerationBlockedException("Grilld's briefly paused for a check, try again shortly.");
        }

        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        if (brief.getScaleTier() == null) {
            throw new IllegalStateException(
                    "Session " + sessionId + " has no scale tier yet - calibrate before generating");
        }

        GenerationRun run = generationRunRepository.save(new GenerationRun(brief.getId()));

        List<String> unresolvedSlotDescriptions = slotRepository.findBySessionIdAndStatus(sessionId, Slot.Status.OPEN)
                .stream()
                .map(Slot::getDescription)
                .toList();

        generationExecutor.execute(() -> runGeneration(run.getId(), brief, unresolvedSlotDescriptions));

        return new GenerationRunResult(run.getId(), run.getStatus().name(), Map.of());
    }

    /**
     * Re-triggers a run {@link GenerationResumeSweep} found stuck at
     * IN_PROGRESS with no recent activity - the case where this JVM
     * restarted mid-run and lost whatever background thread was working on
     * it. Relies on the Python side's own LangGraph checkpointer (§10.5) to
     * resume correctly rather than redo completed work when
     * generateBlueprint() is called again for the same run id - this is a
     * deliberately reduced scope with no reconciliation against Python's own
     * run status first, since langgraph dev's status bookkeeping is itself
     * in-memory/ephemeral right now (see LEARNING.md's Phase 6 task 4 note).
     */
    public void resumeStaleRun(GenerationRun run) {
        ProjectBrief brief = briefRepository.findById(run.getBriefId())
                .orElseThrow(() -> new ResourceNotFoundException("No brief for run " + run.getId()));
        List<String> unresolvedSlotDescriptions = slotRepository
                .findBySessionIdAndStatus(brief.getSessionId(), Slot.Status.OPEN)
                .stream()
                .map(Slot::getDescription)
                .toList();

        generationExecutor.execute(() -> runGeneration(run.getId(), brief, unresolvedSlotDescriptions));
    }

    // Runs off the request thread (see generate()). Deliberately NOT
    // @Transactional: generateBlueprint() is a slow external HTTP call (a
    // full specialist-roster run, real minutes) that also invokes onProgress
    // synchronously as it streams - each invocation does its own .save(),
    // committed independently, so a partially-completed run's rows survive
    // even if a later step fails. Holding one big transaction open across a
    // multi-minute streamed call would be wrong regardless of the failure path.
    private void runGeneration(UUID runId, ProjectBrief brief, List<String> unresolvedSlotDescriptions) {
        // Assemble once up front so a client that subscribes right after generate()
        // returns sees "brief finalized" + the full queued roster immediately,
        // not a blank report until the first specialist starts.
        updateAndBroadcastReport(runId, brief.getScaleTier());
        try {
            aiServiceClient.generateBlueprint(
                    runId, brief.getBriefJson(), brief.getScaleTier(), unresolvedSlotDescriptions,
                    event -> handleProgressEvent(runId, brief.getScaleTier(), event));
            GenerationRun run = generationRunRepository.findById(runId).orElseThrow();
            run.markCompleted();
            generationRunRepository.save(run);
            runReportBroadcaster.publish(runId, run);
        } catch (RuntimeException e) {
            GenerationRun run = generationRunRepository.findById(runId).orElseThrow();
            run.markFailed(e.getMessage());
            generationRunRepository.save(run);
            runReportBroadcaster.publish(runId, run);
        }
    }

    private void handleProgressEvent(UUID runId, String scaleTier, GenerationProgressEvent event) {
        if (event.status() == GenerationProgressEvent.Status.STARTED) {
            // Reuses an existing row rather than always inserting - matters for
            // resumeStaleRun(), where Python may re-report an agent that already
            // has a row from before the restart (COMPLETED or a stale RUNNING).
            AgentExecution execution = agentExecutionRepository.findByRunIdAndAgentName(runId, event.agentName())
                    .orElseGet(() -> new AgentExecution(runId, event.agentName()));
            execution.markStarted();
            agentExecutionRepository.save(execution);
        } else {
            AgentExecution execution = agentExecutionRepository.findByRunIdAndAgentName(runId, event.agentName())
                    .orElseGet(() -> new AgentExecution(runId, event.agentName()));
            String outputRef = event.newFilePaths().isEmpty() ? null : String.join(", ", event.newFilePaths());
            execution.markCompleted(outputRef, event.narration(), event.inputTokens(), event.outputTokens());
            agentExecutionRepository.save(execution);
        }
        updateAndBroadcastReport(runId, scaleTier);
    }

    private void updateAndBroadcastReport(UUID runId, String scaleTier) {
        GenerationRun run = generationRunRepository.findById(runId).orElseThrow();
        run.updateRunReport(runReportService.assemble(runId, scaleTier));
        generationRunRepository.save(run);
        runReportBroadcaster.publish(runId, run);
    }

    public record GenerationRunResult(UUID runId, String status, Map<String, String> files) {
        public GenerationRunResult {
            files = new LinkedHashMap<>(files);
        }
    }
}
