package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.GenerationResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Triggers a full generation run - the specialist roster
 * (docs/product-and-architecture.md §3.2) turning a scale-calibrated brief
 * into the blueprint package. Phase 5 scope: one synchronous call (like the
 * Interrogator/Rubric Agent pattern), blocking until the whole roster
 * finishes - real production traffic needs the async run/stream pattern
 * from docs/decisions-and-technical-architecture.md §11.3, deliberately
 * deferred to Phase 6 (Run Report + SSE + resume sweep) rather than built
 * twice.
 */
@Service
public class GenerationService {

    /**
     * Which file each specialist is expected to have produced, for the
     * placeholder per-agent AgentExecution rows below - Phase 5 only gets
     * the final blocking response, not real per-step webhooks, so this is
     * inferred from the roster's own prompts (grilld_ai_service/specialists/),
     * not observed live. See AgentExecution's class doc for why.
     */
    private static final Map<String, String> AGENT_PRIMARY_OUTPUT = Map.ofEntries(
            Map.entry("market_analyst", "/docs/MARKET_ANALYSIS.md"),
            Map.entry("competition_analyst", "/docs/COMPETITION.md"),
            Map.entry("strategy_agent", "/docs/STRATEGY.md"),
            Map.entry("tech_architect", "/docs/TECH_STACK.md"),
            Map.entry("infra_agent", "/docs/INFRA.md"),
            Map.entry("diagram_agent", "/diagrams/architecture.mmd"),
            Map.entry("roadmap_agent", "/docs/ROADMAP.md"),
            Map.entry("skills_curator", "/docs/SKILLS_NEEDED.md"),
            Map.entry("agent_file_writer", "/agent-kit/AGENTS.md"),
            Map.entry("consistency_auditor", "/docs/CONSISTENCY_REPORT.md")
    );

    private final ProjectBriefRepository briefRepository;
    private final GenerationRunRepository generationRunRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final AiServiceClient aiServiceClient;

    public GenerationService(ProjectBriefRepository briefRepository, GenerationRunRepository generationRunRepository,
                              AgentExecutionRepository agentExecutionRepository, AiServiceClient aiServiceClient) {
        this.briefRepository = briefRepository;
        this.generationRunRepository = generationRunRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // Deliberately NOT @Transactional: generateBlueprint() below is a slow
    // external HTTP call (a full specialist-roster run, real minutes), and
    // holding a database transaction open across it would be wrong even
    // ignoring the failure path. Each repository .save() below commits on
    // its own - which also means the run.markFailed() write in the catch
    // block below actually survives when this method exits via exception,
    // instead of being rolled back with everything else the way it would
    // under one enclosing @Transactional.
    public GenerationRunResult generate(UUID sessionId) {
        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        if (brief.getScaleTier() == null) {
            throw new IllegalStateException(
                    "Session " + sessionId + " has no scale tier yet - calibrate before generating");
        }

        GenerationRun run = generationRunRepository.save(new GenerationRun(brief.getId()));

        try {
            GenerationResult result = aiServiceClient.generateBlueprint(run.getId(), brief.getBriefJson(), brief.getScaleTier());
            persistAgentExecutions(run.getId(), result);
            run.markCompleted("Generated " + result.files().size() + " files across "
                    + AGENT_PRIMARY_OUTPUT.size() + " specialists.");
            generationRunRepository.save(run);
            return new GenerationRunResult(run.getId(), run.getStatus().name(), result.files());
        } catch (RuntimeException e) {
            run.markFailed(e.getMessage());
            generationRunRepository.save(run);
            throw e;
        }
    }

    private void persistAgentExecutions(UUID runId, GenerationResult result) {
        AGENT_PRIMARY_OUTPUT.forEach((agentName, expectedPath) -> {
            AgentExecution execution = new AgentExecution(runId, agentName);
            if (result.files().containsKey(expectedPath)) {
                execution.markCompleted(expectedPath, agentName + " wrote " + expectedPath);
            } else {
                execution.markFailed("Expected output " + expectedPath + " was not found in the run's files");
            }
            agentExecutionRepository.save(execution);
        });
    }

    public record GenerationRunResult(UUID runId, String status, Map<String, String> files) {
        public GenerationRunResult {
            files = new LinkedHashMap<>(files);
        }
    }
}
