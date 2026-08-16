package com.grilld.backend.generation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves assemble() matches the exact format from
 * docs/decisions-and-technical-architecture.md §10.3 - deterministic string
 * building off AgentExecution rows, no Spring context or database needed.
 */
class RunReportServiceTest {

    private final AgentExecutionRepository agentExecutionRepository = mock(AgentExecutionRepository.class);
    private final RunReportService runReportService = new RunReportService(agentExecutionRepository);
    private final UUID runId = UUID.randomUUID();

    @Test
    void freshRunShowsHeaderAndEveryAgentQueued() {
        when(agentExecutionRepository.findByRunIdOrderByStartedAtAsc(runId)).thenReturn(List.of());

        String report = runReportService.assemble(runId, "T1");

        assertEquals("✓ Interrogation complete — brief finalized (T1, Solo Indie MVP)\n"
                + "  Queued: Market Analyst, Competition Analyst, Strategy Agent, Tech Architect, "
                + "Infra Agent, Diagram Agent, Roadmap Agent, Skills Curator, Agent-File Writer, "
                + "Consistency Auditor", report);
    }

    @Test
    void completedRunningAndQueuedAgentsRenderWithTheirOwnSymbols() {
        AgentExecution completed = new AgentExecution(runId, "market_analyst");
        completed.markCompleted("/docs/MARKET_ANALYSIS.md", "Researched the real market for this idea.", 100, 50);

        AgentExecution running = new AgentExecution(runId, "competition_analyst");

        when(agentExecutionRepository.findByRunIdOrderByStartedAtAsc(runId))
                .thenReturn(List.of(completed, running));

        String report = runReportService.assemble(runId, "T2");

        assertTrue(report.contains("✓ Market Analyst — Researched the real market for this idea."),
                "expected a completed agent's narration on its own line, got:\n" + report);
        assertTrue(report.contains("⏳ Competition Analyst — generating..."),
                "expected the in-flight agent marked running, got:\n" + report);
        assertTrue(report.contains("Queued: Strategy Agent, Tech Architect"),
                "expected everything after the running agent still queued, got:\n" + report);
        assertTrue(report.contains("(T2, Small Team Funded MVP)"));
    }

    @Test
    void failedAgentRendersItsErrorInline() {
        AgentExecution failed = new AgentExecution(runId, "tech_architect");
        failed.markFailed("connection dropped mid-stream");

        when(agentExecutionRepository.findByRunIdOrderByStartedAtAsc(runId)).thenReturn(List.of(failed));

        String report = runReportService.assemble(runId, "T0");

        assertTrue(report.contains("✗ Tech Architect — failed: connection dropped mid-stream"),
                "expected a failed agent's error inline, got:\n" + report);
    }
}
