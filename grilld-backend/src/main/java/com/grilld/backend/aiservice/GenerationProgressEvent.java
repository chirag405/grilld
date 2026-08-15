package com.grilld.backend.aiservice;

import java.util.List;

/**
 * One meaningful state change during a streamed generation run
 * (docs/decisions-and-technical-architecture.md §10.2's narration, §10.3's
 * Run Report). Parsed live from the LangGraph server's SSE stream
 * (HttpAiServiceClient) - or replayed synthetically by StubAiServiceClient -
 * and handed to a callback so the caller can persist an AgentExecution row
 * as each specialist finishes, not just once at the very end.
 */
public record GenerationProgressEvent(
        String agentName,
        Status status,
        String narration, // the specialist's own final report - null on STARTED
        List<String> newFilePaths // files newly present after this agent ran - empty on STARTED
) {
    public enum Status {
        STARTED, COMPLETED
    }
}
