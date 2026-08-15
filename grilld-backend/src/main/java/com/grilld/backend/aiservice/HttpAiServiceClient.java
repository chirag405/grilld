package com.grilld.backend.aiservice;

import com.grilld.backend.memory.WorkingContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls the real Python AI service's self-hosted LangGraph server
 * (docs/decisions-and-technical-architecture.md §11.3) instead of returning
 * canned data. Activate with SPRING_PROFILES_ACTIVE=python-ai-service (or add
 * it alongside `local`) - StubAiServiceClient (@Profile("!python-ai-service"))
 * stays the default everywhere else.
 *
 * Deliberately minimal for Phase 3, matching that phase's actual gate ("Spring
 * calls the real Python service and gets a real, if trivial, response") - not
 * the full Interrogator contract. It forwards a plain message and wraps
 * whatever comes back as a next_question with no fact extraction, no new
 * slots, never concluding. The real slot-graph-aware Interrogator - on both
 * the Python side (a LangGraph subgraph) and this client's request/response
 * shape - is Phase 4, built together since the contract has to match on both
 * ends at once.
 *
 * Grilld session id doubles as the LangGraph thread id (1:1, no separate
 * mapping table needed) - carries forward unchanged into Phase 4.
 */
@Component
@Profile("python-ai-service")
public class HttpAiServiceClient implements AiServiceClient {

    private final RestClient restClient;

    public HttpAiServiceClient(@Value("${grilld.ai-service.base-url:http://localhost:2024}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    @Override
    public InterrogatorTurnResult nextTurn(WorkingContext context) {
        ensureThreadExists(context.sessionId().toString());

        String prompt = context.recentTurns().isEmpty()
                ? context.rawIdea()
                : context.recentTurns().get(0).answerText();

        Map<String, Object> requestBody = Map.of(
                "assistant_id", "orchestrator",
                "input", Map.of("messages", List.of(Map.of("role", "user", "content", prompt)))
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/threads/{id}/runs/wait", context.sessionId())
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        String replyText = extractLastAiMessage(response);

        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                replyText, List.of(), null, "text",
                "Phase 3 placeholder response from the real Python service - not real Interrogator logic yet.");

        return new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false);
    }

    private void ensureThreadExists(String threadId) {
        try {
            restClient.post()
                    .uri("/threads")
                    .body(Map.of("thread_id", threadId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict alreadyExists) {
            // Expected on every turn after the first - thread creation isn't idempotent
            // server-side, so a 409 here just means "already set up," not a real error.
        }
    }

    @SuppressWarnings("unchecked")
    private String extractLastAiMessage(Map<String, Object> response) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if ("ai".equals(message.get("type"))) {
                Object content = message.get("content");
                if (content instanceof String text) {
                    return text;
                }
            }
        }
        throw new IllegalStateException("No AI message found in Python service response: " + response);
    }
}
