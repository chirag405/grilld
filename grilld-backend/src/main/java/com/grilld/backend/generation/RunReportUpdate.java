package com.grilld.backend.generation;

import java.util.Arrays;
import java.util.List;

/** One SSE frame / poll response for a run's Run Report (§10.3). */
public record RunReportUpdate(String status, String runReportMd, String failureReason,
                              long completedAgents, int totalAgents, String currentStep,
                              List<StepProgress> steps, List<String> completedDocuments) {
    static RunReportUpdate from(GenerationRun run, List<AgentExecution> executions) {
        List<StepProgress> steps = RunReportService.AGENT_ROSTER.stream().map(agentName -> {
            AgentExecution execution = executions.stream()
                    .filter(candidate -> candidate.getAgentName().equals(agentName)).findFirst().orElse(null);
            if (execution == null) return new StepProgress(agentName, "QUEUED", null, List.of());
            List<String> documents = splitDocuments(execution.getOutputRef());
            return new StepProgress(agentName, execution.getStatus().name(), execution.getNarration(), documents);
        }).toList();
        long completed = steps.stream().filter(step -> "COMPLETED".equals(step.status())).count();
        String current = steps.stream().filter(step -> "RUNNING".equals(step.status()))
                .map(StepProgress::agentName).findFirst()
                .orElse(run.getStatus() == GenerationRun.Status.COMPLETED ? "Package ready" : "Preparing next document");
        List<String> documents = steps.stream().flatMap(step -> step.documents().stream()).distinct().toList();
        return new RunReportUpdate(run.getStatus().name(), run.getRunReportMd(), run.getFailureReason(),
                completed, RunReportService.AGENT_ROSTER.size(), current, steps, documents);
    }

    private static List<String> splitDocuments(String outputRef) {
        return outputRef == null || outputRef.isBlank() ? List.of()
                : Arrays.stream(outputRef.split(",\\s*")).filter(value -> !value.isBlank()).toList();
    }

    public record StepProgress(String agentName, String status, String narration, List<String> documents) {
    }
}
