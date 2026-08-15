package com.grilld.backend.aiservice;

import com.grilld.backend.memory.WorkingContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls the real Interrogator - grilld-ai-service's "interrogator" LangGraph
 * (docs/decisions-and-technical-architecture.md §11.3), a plain StateGraph,
 * not the Orchestrator used for delegation testing in Phase 3. Activate with
 * SPRING_PROFILES_ACTIVE=python-ai-service; StubAiServiceClient
 * (@Profile("!python-ai-service")) stays the default everywhere else.
 *
 * The request/response shape here mirrors interrogation-engine.md §3's
 * contract exactly - WorkingContext's fields become the graph's input state,
 * and the graph's final "turn_result" field (built by generate_turn in
 * grilld_ai_service/interrogator/graph.py) is read directly as
 * InterrogatorTurnResult, not parsed out of a chat message the way Phase 3's
 * placeholder did.
 *
 * Grilld session id doubles as the LangGraph thread id (1:1, no separate
 * mapping table) - unchanged from Phase 3.
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

        Map<String, Object> input = Map.of(
                "session_id", context.sessionId().toString(),
                "raw_idea", context.rawIdea(),
                "compacted_brief_summary", context.compactedBriefSummary() == null ? "" : context.compactedBriefSummary(),
                "recent_turns", context.recentTurns().stream().map(this::toPythonTurn).toList(),
                "open_slots_ranked", context.openSlotsRanked().stream().map(this::toPythonSlot).toList(),
                "answered_topics", context.answeredTopics(),
                "open_gaps", context.openGaps()
        );

        Map<String, Object> requestBody = Map.of("assistant_id", "interrogator", "input", input);

        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/threads/{id}/runs/wait", context.sessionId())
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseTurnResult(finalState);
    }

    private Map<String, Object> toPythonTurn(WorkingContext.RecentTurn turn) {
        return Map.of(
                "turn_number", turn.turnNumber(),
                "question_text", turn.questionText() == null ? "" : turn.questionText(),
                "answer_text", turn.answerText() == null ? "" : turn.answerText()
        );
    }

    private Map<String, Object> toPythonSlot(WorkingContext.RankedSlot slot) {
        return Map.of(
                "slot_key", slot.slotKey(),
                "description", slot.description(),
                "importance", slot.importance(),
                "priority", slot.priority()
        );
    }

    @Override
    public RubricResult evaluateRubric(RubricContext context) {
        Map<String, Object> input = Map.of(
                "session_id", context.sessionId().toString(),
                "brief_json", context.briefJson() == null ? "{}" : context.briefJson(),
                "slots", context.slots().stream().map(this::toPythonSlotSnapshot).toList()
        );

        Map<String, Object> requestBody = Map.of("assistant_id", "rubric", "input", input);

        // Stateless run (no thread) - the Rubric Agent has nothing to persist or
        // resume between calls, unlike the Interrogator's per-session thread.
        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/runs/wait")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseRubricResult(finalState);
    }

    private Map<String, Object> toPythonSlotSnapshot(RubricContext.SlotSnapshot slot) {
        return Map.of(
                "slot_key", slot.slotKey(),
                "status", slot.status(),
                "value", slot.value() == null ? "" : slot.value(),
                "importance", slot.importance()
        );
    }

    @SuppressWarnings("unchecked")
    private RubricResult parseRubricResult(Map<String, Object> finalState) {
        Map<String, Object> rubricResult = (Map<String, Object>) finalState.get("rubric_result");
        if (rubricResult == null) {
            throw new IllegalStateException("Rubric graph returned no rubric_result: " + finalState);
        }

        List<RubricResult.DimensionResult> dimensions = ((List<Map<String, Object>>) rubricResult.get("dimensions"))
                .stream()
                .map(d -> new RubricResult.DimensionResult(
                        (String) d.get("dimension"), (String) d.get("score"), (String) d.get("reasoning")))
                .toList();

        String verdict = (String) rubricResult.get("verdict");
        List<String> openGaps = (List<String>) rubricResult.get("open_gaps");

        return new RubricResult(dimensions, verdict, openGaps == null ? List.of() : openGaps);
    }

    @Override
    public ScaleCalibrationResult calibrateScale(String briefJson) {
        Map<String, Object> input = Map.of("brief_json", briefJson == null ? "{}" : briefJson);
        Map<String, Object> requestBody = Map.of("assistant_id", "scale_calibrator", "input", input);

        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/runs/wait")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseScaleCalibrationResult(finalState);
    }

    @SuppressWarnings("unchecked")
    private ScaleCalibrationResult parseScaleCalibrationResult(Map<String, Object> finalState) {
        Map<String, Object> calibrationResult = (Map<String, Object>) finalState.get("calibration_result");
        if (calibrationResult == null) {
            throw new IllegalStateException("Scale Calibrator returned no calibration_result: " + finalState);
        }
        return new ScaleCalibrationResult(
                (String) calibrationResult.get("tier"),
                (String) calibrationResult.get("reasoning"),
                (List<String>) calibrationResult.get("signals"));
    }

    @Override
    public GenerationResult generateBlueprint(UUID runId, String briefJson, String scaleTier) {
        ensureThreadExists(runId.toString());

        String messageContent = "Here is the project brief (JSON):\n" + (briefJson == null ? "{}" : briefJson)
                + "\n\nThe assigned scale tier is " + scaleTier + ". Begin.";
        Map<String, Object> input = Map.of(
                "messages", List.of(Map.of("role", "user", "content", messageContent))
        );
        Map<String, Object> requestBody = Map.of("assistant_id", "orchestrator", "input", input);

        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/threads/{id}/runs/wait", runId)
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseGenerationResult(finalState);
    }

    @SuppressWarnings("unchecked")
    private GenerationResult parseGenerationResult(Map<String, Object> finalState) {
        Map<String, Object> filesRaw = (Map<String, Object>) finalState.get("files");
        Map<String, String> files = new LinkedHashMap<>();
        if (filesRaw != null) {
            filesRaw.forEach((path, data) -> {
                Map<String, Object> fileData = (Map<String, Object>) data;
                List<String> contentLines = (List<String>) fileData.get("content");
                files.put(path, contentLines == null ? "" : String.join("\n", contentLines));
            });
        }
        return new GenerationResult(files);
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
    private InterrogatorTurnResult parseTurnResult(Map<String, Object> finalState) {
        Map<String, Object> turnResult = (Map<String, Object>) finalState.get("turn_result");
        if (turnResult == null) {
            throw new IllegalStateException("Interrogator graph returned no turn_result: " + finalState);
        }

        List<InterrogatorTurnResult.ExtractedFact> extractedFacts = ((List<Map<String, Object>>) turnResult.get("extracted_facts"))
                .stream()
                .map(f -> new InterrogatorTurnResult.ExtractedFact(
                        (String) f.get("slot_key"), (String) f.get("value"), ((Number) f.get("confidence")).doubleValue()))
                .toList();

        List<InterrogatorTurnResult.NewSlot> newSlots = ((List<Map<String, Object>>) turnResult.get("new_slots"))
                .stream()
                .map(s -> new InterrogatorTurnResult.NewSlot(
                        (String) s.get("key"), (String) s.get("description"), (String) s.get("origin"),
                        ((Number) s.get("importance")).intValue(), (String) s.get("parent_slot_key")))
                .toList();

        List<InterrogatorTurnResult.WaivedSlot> waivedSlots = ((List<Map<String, Object>>) turnResult.get("waived_slots"))
                .stream()
                .map(w -> new InterrogatorTurnResult.WaivedSlot((String) w.get("key"), (String) w.get("reason")))
                .toList();

        boolean readyToConclude = Boolean.TRUE.equals(turnResult.get("ready_to_conclude"));

        InterrogatorTurnResult.NextQuestion nextQuestion = null;
        Map<String, Object> questionMap = (Map<String, Object>) turnResult.get("next_question");
        if (questionMap != null) {
            nextQuestion = new InterrogatorTurnResult.NextQuestion(
                    (String) questionMap.get("text"),
                    (List<String>) questionMap.get("targets_slots"),
                    (String) questionMap.get("technique"),
                    (String) questionMap.get("input_mode"),
                    (String) questionMap.get("why_asking"));
        }

        return new InterrogatorTurnResult(extractedFacts, newSlots, waivedSlots, nextQuestion, readyToConclude);
    }
}
