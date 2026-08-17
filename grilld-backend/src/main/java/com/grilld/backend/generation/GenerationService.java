package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationProgressEvent;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.billing.CreditService;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.GenerationBlockedException;
import com.grilld.backend.common.exception.InsufficientCreditsException;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import com.grilld.backend.session.DiscoverySession;
import com.grilld.backend.session.DiscoverySessionRepository;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.access.AccessDeniedException;
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

    // product-and-architecture.md §10 / decisions-and-technical-architecture.md §9:
    // "Full blueprint | ~50" - the flat pre-authorization charge for one generation
    // run. Per-action costs (interview turns, single-doc regen, phase check-in) are
    // deliberately not metered yet - see LEARNING.md's Phase 7 task 1 note for why.
    static final int FULL_BLUEPRINT_CREDITS = 50;

    private final ProjectBriefRepository briefRepository;
    private final DiscoverySessionRepository discoverySessionRepository;
    private final SlotRepository slotRepository;
    private final GenerationRunRepository generationRunRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final AiServiceClient aiServiceClient;
    private final TaskExecutor generationExecutor;
    private final RunReportService runReportService;
    private final RunReportBroadcaster runReportBroadcaster;
    private final CostCircuitBreakerService costCircuitBreakerService;
    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final PackagerService packagerService;
    private final CreditService creditService;

    public GenerationService(ProjectBriefRepository briefRepository,
                              DiscoverySessionRepository discoverySessionRepository, SlotRepository slotRepository,
                              GenerationRunRepository generationRunRepository,
                              AgentExecutionRepository agentExecutionRepository, AiServiceClient aiServiceClient,
                              TaskExecutor generationExecutor, RunReportService runReportService,
                              RunReportBroadcaster runReportBroadcaster,
                              CostCircuitBreakerService costCircuitBreakerService,
                              GeneratedDocumentRepository generatedDocumentRepository,
                              PackagerService packagerService, CreditService creditService) {
        this.briefRepository = briefRepository;
        this.discoverySessionRepository = discoverySessionRepository;
        this.slotRepository = slotRepository;
        this.generationRunRepository = generationRunRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.aiServiceClient = aiServiceClient;
        this.generationExecutor = generationExecutor;
        this.runReportService = runReportService;
        this.runReportBroadcaster = runReportBroadcaster;
        this.costCircuitBreakerService = costCircuitBreakerService;
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.packagerService = packagerService;
        this.creditService = creditService;
    }

    /**
     * Validates preconditions, verifies {@code requestingUserId} actually
     * owns {@code sessionId} (they're the one about to be charged), and
     * pre-authorizes the flat {@link #FULL_BLUEPRINT_CREDITS} charge before
     * creating the {@link GenerationRun} row - an underfunded account never
     * gets a dangling IN_PROGRESS run, it gets an {@link InsufficientCreditsException}
     * (402) synchronously, before any AI-service call. Once charged, the
     * actual multi-minute specialist-roster call is handed off to
     * {@link #generationExecutor} and the run id returned right away - the
     * caller no longer blocks for the whole run. Watch progress via the
     * run's SSE endpoint or by polling its status.
     */
    public GenerationRunResult generate(UUID sessionId, UUID requestingUserId) {
        if (costCircuitBreakerService.isKillSwitchActive()) {
            throw new GenerationBlockedException("Grilld's briefly paused for a check, try again shortly.");
        }

        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        if (brief.getScaleTier() == null) {
            throw new IllegalStateException(
                    "Session " + sessionId + " has no scale tier yet - calibrate before generating");
        }
        DiscoverySession session = discoverySessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No session " + sessionId));
        if (!session.getUserId().equals(requestingUserId)) {
            throw new AccessDeniedException("Session " + sessionId + " does not belong to the requesting user");
        }

        GenerationRun run = generationRunRepository.save(new GenerationRun(brief.getId()));
        try {
            creditService.deductForRun(requestingUserId, FULL_BLUEPRINT_CREDITS, run.getId(),
                    "GENERATION_RUN:" + run.getId());
        } catch (InsufficientCreditsException e) {
            generationRunRepository.delete(run);
            throw e;
        }
        run.chargeCredits(FULL_BLUEPRINT_CREDITS);
        generationRunRepository.save(run);

        List<String> unresolvedSlotDescriptions = slotRepository.findBySessionIdAndStatus(sessionId, Slot.Status.OPEN)
                .stream()
                .map(Slot::getDescription)
                .toList();

        generationExecutor.execute(() -> runGeneration(run.getId(), requestingUserId, brief, unresolvedSlotDescriptions));

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
        // No re-deduction here: this run was already charged once, in generate(),
        // when it was first created - resuming just re-dispatches the same call.
        DiscoverySession session = discoverySessionRepository.findById(brief.getSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("No session for brief " + brief.getId()));
        List<String> unresolvedSlotDescriptions = slotRepository
                .findBySessionIdAndStatus(brief.getSessionId(), Slot.Status.OPEN)
                .stream()
                .map(Slot::getDescription)
                .toList();

        generationExecutor.execute(() -> runGeneration(run.getId(), session.getUserId(), brief, unresolvedSlotDescriptions));
    }

    // Runs off the request thread (see generate()). Deliberately NOT
    // @Transactional: generateBlueprint() is a slow external HTTP call (a
    // full specialist-roster run, real minutes) that also invokes onProgress
    // synchronously as it streams - each invocation does its own .save(),
    // committed independently, so a partially-completed run's rows survive
    // even if a later step fails. Holding one big transaction open across a
    // multi-minute streamed call would be wrong regardless of the failure path.
    private void runGeneration(UUID runId, UUID userId, ProjectBrief brief, List<String> unresolvedSlotDescriptions) {
        // Assemble once up front so a client that subscribes right after generate()
        // returns sees "brief finalized" + the full queued roster immediately,
        // not a blank report until the first specialist starts.
        updateAndBroadcastReport(runId, brief.getScaleTier());
        try {
            GenerationResult result = aiServiceClient.generateBlueprint(
                    runId, brief.getBriefJson(), brief.getScaleTier(), unresolvedSlotDescriptions,
                    event -> handleProgressEvent(runId, brief.getScaleTier(), event));

            // Persisted here, not incrementally per event - generateBlueprint()'s own
            // accumulated "files" map is already the complete, correct set by the time
            // it returns; there's nothing this would gain from tracking mid-stream, and
            // an event never carried content anyway (only paths - see GenerationProgressEvent).
            result.files().forEach((path, content) ->
                    generatedDocumentRepository.save(new GeneratedDocument(runId, path, content)));

            GenerationRun run = generationRunRepository.findById(runId).orElseThrow();
            run.markCompleted();
            generationRunRepository.save(run);
            runReportBroadcaster.publish(runId, run);

            packagerService.packageRun(runId);
        } catch (RuntimeException e) {
            GenerationRun run = generationRunRepository.findById(runId).orElseThrow();
            run.markFailed(e.getMessage());
            generationRunRepository.save(run);
            runReportBroadcaster.publish(runId, run);
            // FAILED is terminal (GenerationResumeSweep only re-triggers IN_PROGRESS
            // rows), so this can only run once per run - no double-refund risk.
            if (run.getCreditsCharged() > 0) {
                creditService.refundForRun(userId, run.getCreditsCharged(), runId, "GENERATION_RUN_REFUND:" + runId);
            }
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
