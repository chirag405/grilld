package com.grilld.backend.generation;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministically assembles the Run Report (§10.3) from
 * {@code agent_executions} rows - no LLM call, just a rewrite of the
 * narration/status fields already sitting there each time one changes.
 * The roster and its display names/order mirror
 * grilld_ai_service/graph.py's ORCHESTRATOR_SYSTEM_PROMPT delegation order
 * exactly, since that's the real order specialists run in.
 */
@Service
public class RunReportService {

    static final List<String> AGENT_ROSTER = List.of(
            "market_analyst", "competition_analyst", "strategy_agent", "tech_architect",
            "infra_agent", "diagram_agent", "roadmap_agent", "skills_curator",
            "agent_file_writer", "consistency_auditor");

    private static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
            Map.entry("market_analyst", "Market Analyst"),
            Map.entry("competition_analyst", "Competition Analyst"),
            Map.entry("strategy_agent", "Strategy Agent"),
            Map.entry("tech_architect", "Tech Architect"),
            Map.entry("infra_agent", "Infra Agent"),
            Map.entry("diagram_agent", "Diagram Agent"),
            Map.entry("roadmap_agent", "Roadmap Agent"),
            Map.entry("skills_curator", "Skills Curator"),
            Map.entry("agent_file_writer", "Agent-File Writer"),
            Map.entry("consistency_auditor", "Consistency Auditor"));

    // docs/product-and-architecture.md §4's tier table, slash stripped to match
    // the worked Run Report example's "(T1, Solo Indie MVP)" header exactly.
    private static final Map<String, String> TIER_NAMES = Map.of(
            "T0", "Weekend/Learning",
            "T1", "Solo Indie MVP",
            "T2", "Small Team Funded MVP",
            "T3", "Scaling Product");

    private final AgentExecutionRepository agentExecutionRepository;

    public RunReportService(AgentExecutionRepository agentExecutionRepository) {
        this.agentExecutionRepository = agentExecutionRepository;
    }

    public String assemble(UUID runId, String scaleTier) {
        Map<String, AgentExecution> byAgent = new LinkedHashMap<>();
        for (AgentExecution execution : agentExecutionRepository.findByRunIdOrderByStartedAtAsc(runId)) {
            byAgent.put(execution.getAgentName(), execution);
        }

        StringBuilder report = new StringBuilder();
        report.append("✓ Interrogation complete — brief finalized (")
                .append(scaleTier).append(", ").append(tierName(scaleTier)).append(")\n");

        List<String> queued = new ArrayList<>();
        for (String agentName : AGENT_ROSTER) {
            AgentExecution execution = byAgent.get(agentName);
            String displayName = DISPLAY_NAMES.get(agentName);
            if (execution == null) {
                queued.add(displayName);
                continue;
            }
            appendLine(report, execution, displayName);
        }

        if (!queued.isEmpty()) {
            report.append("  Queued: ").append(String.join(", ", queued)).append("\n");
        }

        return report.toString().stripTrailing();
    }

    private void appendLine(StringBuilder report, AgentExecution execution, String displayName) {
        switch (execution.getStatus()) {
            case COMPLETED -> report.append("✓ ").append(displayName)
                    .append(" — ").append(execution.getNarration()).append("\n");
            case FAILED -> report.append("✗ ").append(displayName)
                    .append(" — failed: ").append(execution.getError()).append("\n");
            case RUNNING -> report.append("⏳ ").append(displayName)
                    .append(" — generating...\n");
        }
    }

    private String tierName(String scaleTier) {
        return TIER_NAMES.getOrDefault(scaleTier, scaleTier);
    }
}
