package com.grilld.backend.aiservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves consumeGenerationStream() against a REAL captured SSE transcript
 * (src/test/resources/sse-fixtures/single-agent-run.sse) - not a hand-built
 * fixture guessing at the shape, an actual response from a live run against
 * grilld-ai-service's orchestrator graph (Claude Haiku, verified this phase
 * while designing the streaming architecture). No Spring context, no
 * database, no network - this is pure parsing logic, and the risky part of
 * HttpAiServiceClient.generateBlueprint() to get right.
 */
class HttpAiServiceClientTest {

    private final HttpAiServiceClient client = new HttpAiServiceClient("http://unused", new ObjectMapper());

    @Test
    void parsesARealSingleAgentRunIntoStartedAndCompletedEvents() {
        List<GenerationProgressEvent> events = new ArrayList<>();

        GenerationResult result;
        try (InputStream fixture = getClass().getClassLoader()
                .getResourceAsStream("sse-fixtures/single-agent-run.sse")) {
            result = client.consumeGenerationStream(fixture, events::add);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertEquals(2, events.size(), "expected exactly one STARTED and one COMPLETED for strategy_agent");

        GenerationProgressEvent started = events.get(0);
        assertEquals("strategy_agent", started.agentName());
        assertEquals(GenerationProgressEvent.Status.STARTED, started.status());
        assertTrue(started.newFilePaths().isEmpty());

        GenerationProgressEvent completed = events.get(1);
        assertEquals("strategy_agent", completed.agentName());
        assertEquals(GenerationProgressEvent.Status.COMPLETED, completed.status());
        assertEquals(List.of("/docs/STRATEGY.md"), completed.newFilePaths());
        assertTrue(completed.narration().contains("STRATEGY.md"),
                "expected the specialist's real final report as narration, got: " + completed.narration());

        assertEquals(1, result.files().size());
        assertTrue(result.files().get("/docs/STRATEGY.md")
                .contains("placeholder strategy document"));
    }
}
