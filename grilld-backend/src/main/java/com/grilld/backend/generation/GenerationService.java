package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationProgressEvent;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
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

    public GenerationService(ProjectBriefRepository briefRepository, SlotRepository slotRepository,
                              GenerationRunRepository generationRunRepository,
                              AgentExecutionRepository agentExecutionRepository, AiServiceClient aiServiceClient) {
        this.briefRepository = briefRepository;
        this.slotRepository = slotRepository;
        this.generationRunRepository = generationRunRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // Deliberately NOT @Transactional: generateBlueprint() below is a slow
    // external HTTP call (a full specialist-roster run, real minutes) that
    // now also invokes onProgress synchronously as it streams - each
    // invocation does its own .save(), committed independently, so a
    // partially-completed run's rows survive even if a later step fails.
    // Holding one big transaction open across a multi-minute streamed call
    // would be wrong regardless of the failure path.
    public GenerationRunResult generate(UUID sessionId) {
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

        try {
            GenerationResult result = aiServiceClient.generateBlueprint(
                    run.getId(), brief.getBriefJson(), brief.getScaleTier(), unresolvedSlotDescriptions,
                    event -> handleProgressEvent(run.getId(), event));
            run.markCompleted("Generated " + result.files().size() + " files.");
            generationRunRepository.save(run);
            return new GenerationRunResult(run.getId(), run.getStatus().name(), result.files());
        } catch (RuntimeException e) {
            run.markFailed(e.getMessage());
            generationRunRepository.save(run);
            throw e;
        }
    }

    private void handleProgressEvent(UUID runId, GenerationProgressEvent event) {
        if (event.status() == GenerationProgressEvent.Status.STARTED) {
            agentExecutionRepository.save(new AgentExecution(runId, event.agentName()));
            return;
        }

        AgentExecution execution = agentExecutionRepository.findByRunIdAndAgentName(runId, event.agentName())
                .orElseGet(() -> new AgentExecution(runId, event.agentName()));
        String outputRef = event.newFilePaths().isEmpty() ? null : String.join(", ", event.newFilePaths());
        execution.markCompleted(outputRef, event.narration());
        agentExecutionRepository.save(execution);
    }

    public record GenerationRunResult(UUID runId, String status, Map<String, String> files) {
        public GenerationRunResult {
            files = new LinkedHashMap<>(files);
        }
    }
}
